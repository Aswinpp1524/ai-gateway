package dev.gateway.core.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(
        CircuitBreakerSettings circuitBreaker,
        RetrySettings retry,
        TimeoutSettings timeout
) {

    public record CircuitBreakerSettings(
            float failureRateThreshold,
            int slidingWindowSize,
            long waitDurationInOpenStateSeconds,
            int permittedCallsInHalfOpenState
    ) {
        public Duration waitDurationInOpenState() {
            return Duration.ofSeconds(waitDurationInOpenStateSeconds);
        }
    }

    public record RetrySettings(
            int maxAttempts,
            long initialBackoffMillis,
            double backoffMultiplier,
            long maxBackoffMillis,
            double jitterFactor
    ) {}

    public record TimeoutSettings(long seconds) {
        public Duration duration() {
            return Duration.ofSeconds(seconds);
        }
    }
}