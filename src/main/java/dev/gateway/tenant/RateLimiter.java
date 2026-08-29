package dev.gateway.tenant;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Runs the token-bucket check-and-decrement as a single Lua script (scripts/token_bucket.lua).
 * A GET-then-SET from Java would be wrong under concurrency: two requests racing for the same
 * tenant could both GET the same "1 token left" state, both independently decide "allowed", and
 * both SET the count to 0 - only one of them should have gone through, but the read and the write
 * are two separate round-trips, so a second caller's GET can land after the first caller's GET but
 * before its SET, observing the same stale value. A Lua script sidesteps this because Redis runs
 * it as a single command from the server's point of view - no other client's command can
 * interleave between the script's own read and write, so the whole check-refill-decide-write
 * sequence is indivisible.
 */
@Component
class RateLimiter {

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;
    private final TenantProperties properties;

    RateLimiter(ReactiveStringRedisTemplate redis, TenantProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.script = RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);
    }

    Mono<RateLimitResult> tryAcquire(UUID tenantId, int capacity) {
        String key = "ratelimit:" + tenantId;
        long now = System.currentTimeMillis();
        List<String> keys = List.of(key);
        List<String> args = List.of(
                String.valueOf(capacity), String.valueOf(now), String.valueOf(properties.rateLimitBucketTtlSeconds()));
        return redis.execute(script, keys, args).single().map(RateLimiter::toResult);
    }

    private static RateLimitResult toResult(List values) {
        long allowed = ((Number) values.get(0)).longValue();
        int remaining = ((Number) values.get(1)).intValue();
        long retryAfterMillis = ((Number) values.get(2)).longValue();
        long resetAfterMillis = ((Number) values.get(3)).longValue();
        return new RateLimitResult(allowed == 1, remaining, retryAfterMillis, resetAfterMillis);
    }
}
