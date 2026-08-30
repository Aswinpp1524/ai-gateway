package dev.gateway.metering;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import dev.gateway.tenant.TenantContext;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs after TenantAuthenticationFilter (@Order(1)) and RateLimitFilter (@Order(2)) - both the
 * tenant and their rate-limit standing must already be settled before spending a Redis round
 * trip on a budget check.
 */
@Component
@Order(3)
public class BudgetEnforcementFilter implements WebFilter {

    private final BudgetTracker budgetTracker;
    private final ObjectMapper objectMapper;

    public BudgetEnforcementFilter(BudgetTracker budgetTracker, ObjectMapper objectMapper) {
        this.budgetTracker = budgetTracker;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (ExemptPaths.isExempt(exchange)) {
            return chain.filter(exchange);
        }
        return TenantContext.current()
                .flatMap(tenant -> budgetTracker.currentSpend(tenant.id())
                        .flatMap(spend -> spend >= tenant.monthlyBudgetMicros()
                                ? paymentRequired(exchange)
                                : chain.filter(exchange)));
    }

    private Mono<Void> paymentRequired(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.PAYMENT_REQUIRED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse("Monthly budget exceeded", "budget_exceeded"));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
