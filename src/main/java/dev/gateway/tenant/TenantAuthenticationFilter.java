package dev.gateway.tenant;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the caller's API key to a Tenant and puts it in the Reactor Context (not a
 * ThreadLocal - this pipeline hops threads on every provider WebClient call, so a ThreadLocal
 * would silently lose the tenant partway through a request). Runs before RateLimitFilter, which
 * depends on the tenant already being resolved.
 */
@Component
@Order(1)
public class TenantAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TenantResolver tenantResolver;
    private final ObjectMapper objectMapper;

    public TenantAuthenticationFilter(TenantResolver tenantResolver, ObjectMapper objectMapper) {
        this.tenantResolver = tenantResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (ExemptPaths.isExempt(exchange)) {
            return chain.filter(exchange);
        }
        String rawKey = extractBearerToken(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (rawKey == null) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }
        String keyHash = ApiKeyHasher.sha256Hex(rawKey);
        return tenantResolver.resolve(keyHash)
                .flatMap(maybeTenant -> maybeTenant
                        .<Mono<Void>>map(tenant -> chain.filter(exchange).contextWrite(ctx -> ctx.put(Tenant.class, tenant)))
                        .orElseGet(() -> unauthorized(exchange, "Invalid API key")));
    }

    private static String extractBearerToken(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse(message, "authentication_error"));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
