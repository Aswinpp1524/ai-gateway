package dev.gateway.tenant;

import java.util.UUID;

public record Tenant(UUID id, String name, int rateLimitRpm, long monthlyBudgetMicros) {}
