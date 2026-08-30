package dev.gateway.tenant;

import reactor.core.publisher.Mono;

/**
 * Public read access to the Tenant TenantAuthenticationFilter resolves into the Reactor Context -
 * the sanctioned way for code outside this package (or elsewhere within it) to learn the current
 * tenant, without exposing how it got there.
 */
public final class TenantContext {

    private TenantContext() {}

    public static Mono<Tenant> current() {
        return Mono.deferContextual(ctx -> ctx.<Tenant>getOrEmpty(Tenant.class)
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new IllegalStateException(
                        "No tenant in Reactor context - TenantAuthenticationFilter must run before this"))));
    }
}
