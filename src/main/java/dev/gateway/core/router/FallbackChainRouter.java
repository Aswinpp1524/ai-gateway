package dev.gateway.core.router;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.gateway.core.LlmProvider;
import dev.gateway.core.ProviderException;
import dev.gateway.core.RetryableProviderException;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.resilience.ResilienceProperties;
import dev.gateway.core.resilience.ResilientLlmProvider;
import dev.gateway.core.resilience.TransientFailures;
import dev.gateway.observability.GatewayMetrics;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Replaces the "first provider that supports the model" logic. Walks providers in
 * gateway.fallback-order, each already wrapped in circuit breaker / retry / timeout by
 * ResilientLlmProvider, and falls through to the next only on a retryable failure or an open
 * circuit (TransientFailures.isFalloverEligible) - a terminal failure means the request itself
 * is bad, so trying another provider would just fail identically.
 */
@Component
public class FallbackChainRouter {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainRouter.class);

    private final List<LlmProvider> orderedProviders;
    private final GatewayMetrics gatewayMetrics;

    public FallbackChainRouter(
            List<LlmProvider> rawProviders,
            GatewayRoutingProperties routingProperties,
            ResilienceProperties resilienceProperties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            GatewayMetrics gatewayMetrics) {
        this.gatewayMetrics = gatewayMetrics;
        Map<String, LlmProvider> byName = rawProviders.stream()
                .collect(Collectors.toMap(LlmProvider::name, provider -> wrap(
                        provider, resilienceProperties, circuitBreakerRegistry, retryRegistry, timeLimiterRegistry)));
        this.orderedProviders = routingProperties.fallbackOrder().stream()
                .map(name -> {
                    LlmProvider provider = byName.get(name);
                    if (provider == null) {
                        log.warn("gateway.fallback-order references unknown or disabled provider '{}' - skipping", name);
                    }
                    return provider;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static LlmProvider wrap(
            LlmProvider provider,
            ResilienceProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {
        String name = provider.name();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name, circuitBreakerConfig(properties));
        Retry retry = retryRegistry.retry(name, retryConfig(properties));
        TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter(name, timeLimiterConfig(properties));
        return new ResilientLlmProvider(provider, circuitBreaker, retry, timeLimiter, intervalFunction(properties));
    }

    private static CircuitBreakerConfig circuitBreakerConfig(ResilienceProperties properties) {
        ResilienceProperties.CircuitBreakerSettings settings = properties.circuitBreaker();
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .build();
    }

    private static RetryConfig retryConfig(ResilienceProperties properties) {
        return RetryConfig.custom()
                .maxAttempts(properties.retry().maxAttempts())
                .intervalFunction(intervalFunction(properties))
                .retryOnException(TransientFailures::isRetryEligible)
                .build();
    }

    private static TimeLimiterConfig timeLimiterConfig(ResilienceProperties properties) {
        return TimeLimiterConfig.custom().timeoutDuration(properties.timeout().duration()).build();
    }

    private static IntervalFunction intervalFunction(ResilienceProperties properties) {
        ResilienceProperties.RetrySettings settings = properties.retry();
        return IntervalFunction.ofExponentialRandomBackoff(
                settings.initialBackoffMillis(), settings.backoffMultiplier(),
                settings.jitterFactor(), settings.maxBackoffMillis());
    }

    public Mono<ChatResponse> complete(ChatRequest request) {
        return attemptComplete(request, matchingProviders(request.model()));
    }

    public Flux<ChatChunk> stream(ChatRequest request) {
        return attemptStream(request, matchingProviders(request.model()));
    }

    private List<LlmProvider> matchingProviders(String model) {
        List<LlmProvider> matching = orderedProviders.stream().filter(provider -> provider.supports(model)).toList();
        if (matching.isEmpty()) {
            throw new NoProviderAvailableException(model);
        }
        return matching;
    }

    private Mono<ChatResponse> attemptComplete(ChatRequest request, List<LlmProvider> remaining) {
        LlmProvider provider = remaining.get(0);
        List<LlmProvider> rest = remaining.subList(1, remaining.size());
        return provider.complete(request)
                .onErrorResume(error -> {
                    if (error instanceof ProviderException providerException) {
                        gatewayMetrics.recordProviderFailure(provider.name(), providerException.retryable());
                    }
                    if (!rest.isEmpty() && TransientFailures.isFalloverEligible(error)) {
                        log.info("Provider {} failed ({}), falling through to {}",
                                provider.name(), error.getMessage(), rest.get(0).name());
                        gatewayMetrics.recordFallback(provider.name(), rest.get(0).name());
                        return attemptComplete(request, rest);
                    }
                    return Mono.error(normalizeFinalError(error));
                });
    }

    private Flux<ChatChunk> attemptStream(ChatRequest request, List<LlmProvider> remaining) {
        LlmProvider provider = remaining.get(0);
        List<LlmProvider> rest = remaining.subList(1, remaining.size());
        return provider.stream(request)
                .doOnCancel(() -> {
                    log.warn("Stream cancelled by client while using provider={}", provider.name());
                    gatewayMetrics.recordStreamCancellation(provider.name());
                })
                .switchOnFirst((signal, flux) -> {
                    if (signal.hasError()) {
                        Throwable error = signal.getThrowable();
                        if (error instanceof ProviderException providerException) {
                            gatewayMetrics.recordProviderFailure(provider.name(), providerException.retryable());
                        }
                        if (!rest.isEmpty() && TransientFailures.isFalloverEligible(error)) {
                            log.info("Provider {} failed before first chunk ({}), falling through to {}",
                                    provider.name(), error.getMessage(), rest.get(0).name());
                            gatewayMetrics.recordFallback(provider.name(), rest.get(0).name());
                            return attemptStream(request, rest);
                        }
                        return Flux.error(normalizeFinalError(error));
                    }
                    return flux;
                });
    }

    /**
     * CallNotPermittedException isn't a ProviderException, so if it's the LAST error (every
     * provider exhausted - the only way this reaches here, since a mid-chain
     * CallNotPermittedException would already have been caught by the fallover branch above),
     * ApiExceptionHandler wouldn't recognise it and it would fall through to a generic 500.
     * Normalize it into the existing exception hierarchy instead - an open circuit is, by
     * definition, a condition that may clear later.
     */
    private static Throwable normalizeFinalError(Throwable error) {
        if (error instanceof CallNotPermittedException) {
            return new RetryableProviderException(
                    "router", "All providers exhausted; last failure: circuit open - " + error.getMessage(), error);
        }
        return error;
    }
}