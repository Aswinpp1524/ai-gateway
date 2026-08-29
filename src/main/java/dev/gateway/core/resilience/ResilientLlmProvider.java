package dev.gateway.core.resilience;

import java.time.Duration;
import java.util.function.Supplier;

import dev.gateway.core.LlmProvider;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Decorates a single LlmProvider with circuit breaker, retry, and timeout.
 *
 * complete() uses Resilience4j's own Mono operators throughout, including RetryOperator - a Mono
 * is all-or-nothing, so there's no partial result a naive retry could duplicate. Operator order
 * (TimeLimiter innermost, CircuitBreaker next, Retry outermost) follows Resilience4j's documented
 * recommendation: each retry attempt is independently time-bounded and circuit-guarded.
 *
 * stream() deliberately does NOT use RetryOperator, even though that looks like reinventing a
 * wheel. Resilience4j's Flux retry resubscribes the WHOLE upstream Flux on error, with no
 * awareness of how many elements downstream already received. Once a client has seen real chunks,
 * "retrying" would append a second, unrelated completion after the first - corrupting the
 * response, not recovering it. So stream() drives its own loop gated by Flux.switchOnFirst: it
 * only reattempts when the FIRST signal from an attempt is an error, which is the only case where
 * nothing has reached the client yet. Backoff timing reuses the same exponential-with-jitter
 * IntervalFunction the Mono path's Retry uses internally, just without Resilience4j's
 * resubscription machinery driving it.
 */
public class ResilientLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final IntervalFunction streamBackoff;
    private final int maxStreamAttempts;
    private final Duration streamTimeout;

    public ResilientLlmProvider(
            LlmProvider delegate,
            CircuitBreaker circuitBreaker,
            Retry retry,
            TimeLimiter timeLimiter,
            IntervalFunction streamBackoff) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.streamBackoff = streamBackoff;
        this.maxStreamAttempts = retry.getRetryConfig().getMaxAttempts();
        this.streamTimeout = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean supports(String model) {
        return delegate.supports(model);
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        return delegate.complete(request)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        return streamAttempt(() -> delegate.stream(request), 1);
    }

    private Flux<ChatChunk> streamAttempt(Supplier<Flux<ChatChunk>> attempt, int attemptNumber) {
        return attempt.get()
                .timeout(streamTimeout)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .switchOnFirst((signal, flux) -> {
                    if (signal.hasError()) {
                        Throwable error = signal.getThrowable();
                        if (TransientFailures.isRetryEligible(error) && attemptNumber < maxStreamAttempts) {
                            Duration delay = Duration.ofMillis(streamBackoff.apply(attemptNumber));
                            return Mono.delay(delay).thenMany(streamAttempt(attempt, attemptNumber + 1));
                        }
                    }
                    return flux;
                });
    }
}