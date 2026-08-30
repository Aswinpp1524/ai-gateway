package dev.gateway.metering;

import java.util.UUID;

record UsageLogEntry(
        UUID tenantId,
        String requestedModel,
        String servedBy,
        int promptTokens,
        int completionTokens,
        long costMicros,
        boolean cacheHit,
        int latencyMs
) {}
