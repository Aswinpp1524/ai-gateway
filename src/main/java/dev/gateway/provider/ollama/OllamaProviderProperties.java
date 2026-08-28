package dev.gateway.provider.ollama;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.ollama")
public record OllamaProviderProperties(
        boolean enabled,
        String baseUrl,
        long timeoutSeconds,
        List<String> models
) {
    public OllamaProviderProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }
}