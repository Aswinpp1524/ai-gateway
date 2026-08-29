package dev.gateway.tenant;

import java.util.UUID;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
class TenantRepository {

    private final DatabaseClient databaseClient;

    TenantRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Empty when the key is unknown OR its api_keys row is inactive - both cases are
     * indistinguishable to the caller and both correctly mean "reject this request". */
    Mono<Tenant> findByApiKeyHash(String keyHash) {
        return databaseClient.sql("""
                        SELECT t.id, t.name, t.rate_limit_rpm
                        FROM api_keys ak
                        JOIN tenants t ON t.id = ak.tenant_id
                        WHERE ak.key_hash = :keyHash AND ak.active = true
                        """)
                .bind("keyHash", keyHash)
                .map((row, metadata) -> new Tenant(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("rate_limit_rpm", Integer.class)))
                .first();
    }
}
