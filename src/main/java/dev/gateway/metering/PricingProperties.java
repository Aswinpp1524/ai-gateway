package dev.gateway.metering;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prices are expressed directly in micros per million tokens (not dollars) so the whole
 * cost pipeline - config value through to cost_micros - never touches a double or BigDecimal.
 */
@ConfigurationProperties(prefix = "gateway.pricing")
public record PricingProperties(ModelPricing defaultPricing, Map<String, ModelPricing> models) {

    public record ModelPricing(long inputPricePerMillionMicros, long outputPricePerMillionMicros) {}

    public ModelPricing pricingFor(String model) {
        ModelPricing pricing = models.get(model);
        return pricing != null ? pricing : defaultPricing;
    }
}
