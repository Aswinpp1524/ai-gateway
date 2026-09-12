package dev.gateway.provider.stub;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.providers.stub")
public record StubProviderProperties(
        boolean enabled,
        List<String> models,
        String responseContent,
        int promptTokens,
        int completionTokens,
        long simulatedLatencyMillis,
        int streamChunkCount
) {
    public StubProviderProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
