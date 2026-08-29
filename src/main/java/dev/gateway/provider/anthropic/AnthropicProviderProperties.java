package dev.gateway.provider.anthropic;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.anthropic")
public record AnthropicProviderProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        long timeoutSeconds,
        int defaultMaxTokens,
        List<String> models
) {
    public AnthropicProviderProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }
}