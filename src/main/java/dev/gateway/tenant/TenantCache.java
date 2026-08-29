package dev.gateway.tenant;

import java.util.Optional;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
class TenantCache {

    private static final String KEY_PREFIX = "tenant:apikey:";
    private static final String MISS_SENTINEL = "";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TenantProperties properties;

    TenantCache(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper, TenantProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Mono completes empty on a cache miss (not cached at all - go to the DB); Mono of
     * Optional.empty() is a cached NEGATIVE result (known-invalid key, skip the DB). */
    Mono<Optional<Tenant>> get(String keyHash) {
        return redis.opsForValue().get(KEY_PREFIX + keyHash).map(this::deserialize);
    }

    Mono<Void> put(String keyHash, Optional<Tenant> tenant) {
        String value = tenant.map(objectMapper::writeValueAsString).orElse(MISS_SENTINEL);
        return redis.opsForValue().set(KEY_PREFIX + keyHash, value, properties.cacheTtl()).then();
    }

    private Optional<Tenant> deserialize(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(objectMapper.readValue(value, Tenant.class));
    }
}
