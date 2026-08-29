package dev.gateway.tenant;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.tenant")
public record TenantProperties(long cacheTtlSeconds, long rateLimitBucketTtlSeconds) {

    public Duration cacheTtl() {
        return Duration.ofSeconds(cacheTtlSeconds);
    }
}
