package dev.gateway.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * resilience4j-spring-boot3's own CircuitBreakerMetricsAutoConfiguration does NOT bind metrics
 * for circuit breakers created programmatically (as FallbackChainRouter does, one per provider,
 * via CircuitBreakerRegistry.circuitBreaker(name, config) rather than
 * resilience4j.circuitbreaker.instances.* YAML) - verified empirically: /actuator/circuitbreakers
 * shows the breakers exist, but /actuator/prometheus exposes zero resilience4j_* series for them.
 * Its auto-configured TaggedCircuitBreakerMetricsPublisher bean implements MetricsPublisher, but
 * the CircuitBreakerRegistry bean's own construction only wires in beans of type
 * RegistryEventConsumer<CircuitBreaker> - so that publisher is created but never actually
 * attached to the registry.
 *
 * This is NOT a reimplementation of circuit breaker metrics - it's resilience4j's own
 * TaggedCircuitBreakerMetrics (the class that DOES bind dynamically, by listening for the
 * registry's onEntryAdded events once bound), just wired explicitly since the autoconfiguration
 * doesn't do it for this app's bean topology.
 */
@Configuration
class CircuitBreakerMetricsConfig {

    @Bean
    TaggedCircuitBreakerMetrics taggedCircuitBreakerMetrics(CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
