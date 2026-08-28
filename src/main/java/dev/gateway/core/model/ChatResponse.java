package dev.gateway.core.model;

import java.time.Instant;

public record ChatResponse(
        String id,
        String model,
        String servedBy,
        String content,
        Usage usage,
        FinishReason finishReason,
        boolean cacheHit,
        Instant createdAt
) {}