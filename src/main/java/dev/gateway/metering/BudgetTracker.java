package dev.gateway.metering;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
class BudgetTracker {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String KEY_PREFIX = "budget:";
    // Safety-net cleanup only - the actual monthly "reset" is the key name rolling over every
    // month (a new month means a new key, implicitly starting at zero), not this TTL.
    private static final Duration KEY_TTL = Duration.ofDays(40);

    private final ReactiveStringRedisTemplate redis;

    BudgetTracker(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    Mono<Long> currentSpend(UUID tenantId) {
        return redis.opsForValue().get(key(tenantId)).map(Long::parseLong).defaultIfEmpty(0L);
    }

    Mono<Long> addSpend(UUID tenantId, long costMicros) {
        String key = key(tenantId);
        return redis.opsForValue().increment(key, costMicros)
                .flatMap(newTotal -> redis.expire(key, KEY_TTL).thenReturn(newTotal));
    }

    private static String key(UUID tenantId) {
        String month = MONTH_FORMAT.format(Instant.now().atZone(ZoneOffset.UTC));
        return KEY_PREFIX + tenantId + ":" + month;
    }
}
