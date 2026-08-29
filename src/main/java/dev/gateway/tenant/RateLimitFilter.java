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
 * Runs after TenantAuthenticationFilter (@Order(1)) - the tenant is always present in context
 * here for any non-exempt path, since auth already rejected anything without one.
 */
@Component
@Order(2)
public class RateLimitFilter implements WebFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (ExemptPaths.isExempt(exchange)) {
            return chain.filter(exchange);
        }
        return Mono.deferContextual(ctx -> {
            Tenant tenant = ctx.get(Tenant.class);
            return rateLimiter.tryAcquire(tenant.id(), tenant.rateLimitRpm())
                    .flatMap(result -> {
                        addRateLimitHeaders(exchange, tenant, result);
                        return result.allowed() ? chain.filter(exchange) : tooManyRequests(exchange, result);
                    });
        });
    }

    private static void addRateLimitHeaders(ServerWebExchange exchange, Tenant tenant, RateLimitResult result) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.add("X-RateLimit-Limit", String.valueOf(tenant.rateLimitRpm()));
        headers.add("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        headers.add("X-RateLimit-Reset", String.valueOf(millisToSeconds(result.resetAfterMillis())));
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, RateLimitResult result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(millisToSeconds(result.retryAfterMillis())));
        byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse("Rate limit exceeded", "rate_limit_error"));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private static long millisToSeconds(long millis) {
        return (long) Math.ceil(millis / 1000.0);
    }
}
