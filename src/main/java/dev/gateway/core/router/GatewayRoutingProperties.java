package dev.gateway.core.router;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayRoutingProperties(List<String> fallbackOrder) {
    public GatewayRoutingProperties {
        fallbackOrder = fallbackOrder == null ? List.of() : List.copyOf(fallbackOrder);
    }
}