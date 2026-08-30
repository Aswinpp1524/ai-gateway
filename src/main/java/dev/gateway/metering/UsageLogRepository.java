package dev.gateway.metering;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
class UsageLogRepository {

    private final DatabaseClient databaseClient;

    UsageLogRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Mono<Void> insert(UsageLogEntry entry) {
        return databaseClient.sql("""
                        INSERT INTO usage_log
                            (tenant_id, requested_model, served_by, prompt_tokens, completion_tokens,
                             cost_micros, cache_hit, latency_ms)
                        VALUES (:tenantId, :requestedModel, :servedBy, :promptTokens, :completionTokens,
                                :costMicros, :cacheHit, :latencyMs)
                        """)
                .bind("tenantId", entry.tenantId())
                .bind("requestedModel", entry.requestedModel())
                .bind("servedBy", entry.servedBy())
                .bind("promptTokens", entry.promptTokens())
                .bind("completionTokens", entry.completionTokens())
                .bind("costMicros", entry.costMicros())
                .bind("cacheHit", entry.cacheHit())
                .bind("latencyMs", entry.latencyMs())
                .then();
    }
}
