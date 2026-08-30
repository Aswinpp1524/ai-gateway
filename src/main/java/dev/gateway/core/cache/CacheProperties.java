package dev.gateway.core.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.cache")
public record CacheProperties(
        boolean enabled,
        double similarityThreshold,
        int ttlHours,
        double maxCacheableTemperature
) {

    public Duration ttl() {
        return Duration.ofHours(ttlHours);
    }
}
