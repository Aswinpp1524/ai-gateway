package dev.gateway.tenant;

import java.util.UUID;

record Tenant(UUID id, String name, int rateLimitRpm) {}
