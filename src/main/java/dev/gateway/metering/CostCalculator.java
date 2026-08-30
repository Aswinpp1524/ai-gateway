package dev.gateway.metering;

import org.springframework.stereotype.Component;

@Component
class CostCalculator {

    private final PricingProperties pricing;

    CostCalculator(PricingProperties pricing) {
        this.pricing = pricing;
    }

    /** Integer micros throughout, never floating point - the schema uses cost_micros for exactly
     * this reason. Cache hits always cost zero; that's the whole cost story for them. */
    long costMicros(String model, int promptTokens, int completionTokens, boolean cacheHit) {
        if (cacheHit) {
            return 0L;
        }
        PricingProperties.ModelPricing modelPricing = pricing.pricingFor(model);
        long promptCost = (long) promptTokens * modelPricing.inputPricePerMillionMicros() / 1_000_000L;
        long completionCost = (long) completionTokens * modelPricing.outputPricePerMillionMicros() / 1_000_000L;
        return promptCost + completionCost;
    }
}
