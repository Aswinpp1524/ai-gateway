package dev.gateway.core.cache;

import java.time.Duration;

import dev.gateway.core.model.ChatResponse;

/**
 * providerLatency is Duration.ZERO on a hit (no provider was ever called - the entire latency of
 * a hit IS gateway overhead) and the measured wall-clock of the router call on a miss, so the
 * caller can compute overhead = total - providerLatency without SemanticCache needing to know
 * anything about how that gets recorded as a metric.
 */
public record CacheResult(ChatResponse response, boolean hit, Double similarity, Duration providerLatency) {}
