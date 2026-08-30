package dev.gateway.core.cache;

import dev.gateway.core.model.ChatResponse;

public record CacheResult(ChatResponse response, boolean hit, Double similarity) {}
