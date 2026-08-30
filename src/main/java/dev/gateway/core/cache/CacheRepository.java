package dev.gateway.core.cache;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
class CacheRepository {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    CacheRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    /**
     * tenant_id, model, and expires_at are all in the WHERE clause - never a post-filter applied
     * to retrieved neighbours, which would risk leaking one tenant's cached response to another
     * if it merely happened to rank as the nearest vector.
     */
    Mono<CacheEntry> findSimilar(UUID tenantId, String model, String embeddingLiteral, double maxDistance) {
        return databaseClient.sql("""
                        SELECT id, response_json, 1 - (embedding <=> :embedding::vector) AS similarity
                        FROM cache_entries
                        WHERE tenant_id = :tenantId
                          AND model = :model
                          AND expires_at > now()
                          AND embedding <=> :embedding::vector <= :maxDistance
                        ORDER BY embedding <=> :embedding::vector
                        LIMIT 1
                        """)
                .bind("tenantId", tenantId)
                .bind("model", model)
                .bind("embedding", embeddingLiteral)
                .bind("maxDistance", maxDistance)
                .map((row, metadata) -> new CacheEntry(
                        row.get("id", UUID.class),
                        row.get("similarity", Double.class),
                        objectMapper.readValue(row.get("response_json", String.class), CachedResponsePayload.class)))
                .first();
    }

    Mono<Void> insert(
            UUID tenantId, String model, String promptText, String embeddingLiteral,
            CachedResponsePayload payload, Instant expiresAt) {
        String responseJson = objectMapper.writeValueAsString(payload);
        return databaseClient.sql("""
                        INSERT INTO cache_entries (tenant_id, model, prompt_text, embedding, response_json, expires_at)
                        VALUES (:tenantId, :model, :promptText, :embedding::vector, :responseJson::jsonb, :expiresAt)
                        """)
                .bind("tenantId", tenantId)
                .bind("model", model)
                .bind("promptText", promptText)
                .bind("embedding", embeddingLiteral)
                .bind("responseJson", responseJson)
                .bind("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .then();
    }

    Mono<Void> incrementHitCount(UUID id) {
        return databaseClient.sql("UPDATE cache_entries SET hit_count = hit_count + 1 WHERE id = :id")
                .bind("id", id)
                .then();
    }
}
