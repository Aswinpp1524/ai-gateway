package dev.gateway.metering;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.gateway.observability.GatewayMetrics;

import reactor.core.publisher.Mono;

@Component
public class MeteringService {

    private static final Logger log = LoggerFactory.getLogger(MeteringService.class);

    private final CostCalculator costCalculator;
    private final UsageLogRepository usageLogRepository;
    private final BudgetTracker budgetTracker;
    private final GatewayMetrics gatewayMetrics;

    public MeteringService(
            CostCalculator costCalculator, UsageLogRepository usageLogRepository,
            BudgetTracker budgetTracker, GatewayMetrics gatewayMetrics) {
        this.costCalculator = costCalculator;
        this.usageLogRepository = usageLogRepository;
        this.budgetTracker = budgetTracker;
        this.gatewayMetrics = gatewayMetrics;
    }

    /**
     * Fire-and-forget for both the usage_log write and the Redis spend increment. Awaiting the
     * spend increment wouldn't actually close BudgetEnforcementFilter's race - a request's cost
     * is only known after it completes, so the filter is always checking spend as of the last
     * completed request, never this one. Awaiting here would only narrow that window from "the
     * whole request's duration" down to "one Redis round trip," at the cost of latency on every
     * single request - not a trade worth making for what is inherently a soft budget cap.
     *
     * usage_log only ever gets a row from here, i.e. successful requests only - a failed request
     * consumed no tokens and cost nothing, so there's nothing meaningful to log for it.
     */
    public void record(
            UUID tenantId, String requestedModel, String servedBy, int promptTokens, int completionTokens,
            boolean cacheHit, long latencyMs) {
        long costMicros = costCalculator.costMicros(requestedModel, promptTokens, completionTokens, cacheHit);

        UsageLogEntry entry = new UsageLogEntry(
                tenantId, requestedModel, servedBy, promptTokens, completionTokens, costMicros, cacheHit,
                (int) latencyMs);
        usageLogRepository.insert(entry)
                .onErrorResume(e -> logAndSwallow("usage_log write", e))
                .subscribe();

        budgetTracker.addSpend(tenantId, costMicros)
                .onErrorResume(e -> logAndSwallow("budget spend update", e))
                .subscribe();

        gatewayMetrics.recordCost(tenantId.toString(), requestedModel, servedBy, costMicros);
    }

    private static <T> Mono<T> logAndSwallow(String action, Throwable error) {
        log.warn("{} failed", action, error);
        return Mono.empty();
    }
}
