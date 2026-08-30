package dev.gateway.core.cache;

import java.util.UUID;

record CacheEntry(UUID id, double similarity, CachedResponsePayload payload) {}
