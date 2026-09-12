package dev.gateway.provider.stub;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.gateway.core.LlmProvider;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Usage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Test-only provider that does no I/O at all - no HTTP call, no external process. It exists to
 * isolate gateway overhead (auth, rate limiting, budget enforcement, routing, resilience
 * wrapping, metering) from provider latency during load testing, so a load test against it
 * measures this codebase, not a vendor's network. Disabled by default; see
 * application-loadtest.yml for how it's enabled.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.providers.stub", name = "enabled", havingValue = "true")
public class StubProvider implements LlmProvider {

    private final StubProviderProperties properties;

    public StubProvider(StubProviderProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public boolean supports(String model) {
        return properties.models().contains(model);
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        Mono<ChatResponse> response = Mono.fromSupplier(() -> new ChatResponse(
                UUID.randomUUID().toString(),
                request.model(),
                name(),
                properties.responseContent(),
                usage(),
                FinishReason.STOP,
                false,
                Instant.now()));
        return applySimulatedLatency(response);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        Flux<ChatChunk> chunks = Flux.fromIterable(splitIntoChunks(properties.responseContent(), properties.streamChunkCount()))
                .map(ChatChunk::of)
                .concatWithValues(ChatChunk.terminal(usage(), FinishReason.STOP, name()));
        return properties.simulatedLatencyMillis() > 0
                ? Mono.delay(Duration.ofMillis(properties.simulatedLatencyMillis())).thenMany(chunks)
                : chunks;
    }

    private Usage usage() {
        return Usage.exact(properties.promptTokens(), properties.completionTokens());
    }

    private Mono<ChatResponse> applySimulatedLatency(Mono<ChatResponse> response) {
        return properties.simulatedLatencyMillis() > 0
                ? response.delayElement(Duration.ofMillis(properties.simulatedLatencyMillis()))
                : response;
    }

    /** Splits on word boundaries into roughly `chunkCount` pieces so streaming clients see more
     * than one frame; a non-positive or too-large chunkCount just streams word-by-word. */
    private static List<String> splitIntoChunks(String content, int chunkCount) {
        String[] words = content.split("(?<=\\s)");
        if (chunkCount <= 0 || chunkCount >= words.length) {
            return List.of(words);
        }
        List<String> chunks = new ArrayList<>(chunkCount);
        int perChunk = (words.length + chunkCount - 1) / chunkCount;
        for (int i = 0; i < words.length; i += perChunk) {
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < Math.min(i + perChunk, words.length); j++) {
                chunk.append(words[j]);
            }
            chunks.add(chunk.toString());
        }
        return chunks;
    }
}
