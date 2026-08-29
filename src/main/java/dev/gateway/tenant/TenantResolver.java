package dev.gateway.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
class TenantResolver {

    private final TenantCache cache;
    private final TenantRepository repository;

    TenantResolver(TenantCache cache, TenantRepository repository) {
        this.cache = cache;
        this.repository = repository;
    }

    Mono<Optional<Tenant>> resolve(String keyHash) {
        return cache.get(keyHash)
                .switchIfEmpty(Mono.defer(() -> repository.findByApiKeyHash(keyHash)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(result -> cache.put(keyHash, result).thenReturn(result))));
    }
}
