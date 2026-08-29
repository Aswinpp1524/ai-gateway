package dev.gateway.provider.openai;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.openai")
public record OpenAiProviderProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        long timeoutSeconds,
        List<String> models
) {
    public OpenAiProviderProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
