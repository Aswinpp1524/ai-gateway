package dev.gateway.observability;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * The one place metric names, types, and tags for this gateway are defined - keeps them from
 * being scattered as string literals across the controller, router, and resilience layers that
 * each know a piece of the request lifecycle. Circuit breaker state is NOT defined here -
 * resilience4j-spring-boot3's CircuitBreakerMetricsAutoConfiguration already binds every
 * CircuitBreaker created through CircuitBreakerRegistry (including ones FallbackChainRouter
 * creates programmatically), exposed as resilience4j_circuitbreaker_state.
 *
 * CARDINALITY WARNING: tenant is a tag on gateway.requests and both latency timers. Fine at demo
 * scale (a handful of tenants), but in production this multiplies every series on those metrics
 * by tenant count - and the latency timers are percentile histograms, already ~20-30 bucket
 * series per tag combination before tenant even enters. At scale, move tenant off these labels
 * entirely: attach it as a Prometheus exemplar (a single request-scoped value stamped on one
 * histogram observation, not a persistent label dimension) or correlate via structured logs keyed
 * by request ID instead of baking it into every aggregate metric's label set.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** overhead may be null - callers that can't honestly attribute time between "waiting on a
     * provider" and "our own code" (see ChatCompletionController) skip it rather than guess. */
    public void recordRequest(
            String tenant, String model, String provider, boolean cacheHit, String outcome,
            Duration total, Duration overhead) {
        Tags tags = Tags.of(
                "tenant", tenant, "model", model, "provider", provider,
                "cache", cacheHit ? "hit" : "miss", "outcome", outcome);
        registry.counter("gateway.requests", tags).increment();
        timer("gateway.request.duration", tags).record(total);
        if (overhead != null) {
            timer("gateway.request.overhead", tags).record(overhead);
        }
    }

    public void recordTokens(
            String tenant, String model, String provider, boolean estimated, int promptTokens, int completionTokens) {
        Tags base = Tags.of(
                "tenant", tenant, "model", model, "provider", provider, "estimated", String.valueOf(estimated));
        registry.counter("gateway.tokens", base.and("type", "prompt")).increment(promptTokens);
        registry.counter("gateway.tokens", base.and("type", "completion")).increment(completionTokens);
    }

    public void recordProviderFailure(String provider, boolean retryable) {
        registry.counter("gateway.provider.failures", "provider", provider, "retryable", String.valueOf(retryable))
                .increment();
    }

    public void recordFallback(String from, String to) {
        registry.counter("gateway.router.fallbacks", "from", from, "to", to).increment();
    }

    public void recordStreamCancellation(String provider) {
        registry.counter("gateway.stream.cancellations", "provider", provider).increment();
    }

    public void recordCost(String tenant, String model, String provider, long costMicros) {
        registry.counter("gateway.cost.micros", "tenant", tenant, "model", model, "provider", provider)
                .increment(costMicros);
    }

    private Timer timer(String name, Tags tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }
}
