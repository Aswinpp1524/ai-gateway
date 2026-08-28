package dev.gateway.provider.ollama;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import dev.gateway.core.AbstractLlmProvider;
import dev.gateway.core.ProviderException;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Usage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "gateway.providers.ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OllamaProvider extends AbstractLlmProvider {

    private static final String CHAT_ENDPOINT = "/api/chat";

    private final OllamaProviderProperties properties;

    public OllamaProvider(OllamaProviderProperties properties, WebClient.Builder builder) {
        super(builder, properties.baseUrl(), Duration.ofSeconds(properties.timeoutSeconds()));
        this.properties = properties;
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public boolean supports(String model) {
        return properties.models().contains(model);
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        OllamaChatRequest body = buildRequestBody(request, false);
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaChatResponse.class)
                .map(this::toChatResponse)
                .onErrorMap(this::mapError);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        OllamaChatRequest body = buildRequestBody(request, true);
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(OllamaChatResponse.class)
                .map(this::toChatChunk)
                .onErrorMap(this::mapError);
    }

    private OllamaChatRequest buildRequestBody(ChatRequest request, boolean stream) {
        List<OllamaMessage> messages = request.messages().stream()
                .map(m -> new OllamaMessage(m.role().name().toLowerCase(), m.content()))
                .toList();
        OllamaOptions options = (request.temperature() == null && request.maxTokens() == null)
                ? null
                : new OllamaOptions(request.temperature(), request.maxTokens());
        return new OllamaChatRequest(request.model(), messages, stream, options);
    }

    private ChatResponse toChatResponse(OllamaChatResponse response) {
        return new ChatResponse(
                UUID.randomUUID().toString(),
                response.model(),
                name(),
                response.message().content(),
                usageOf(response),
                mapFinishReason(response.doneReason()),
                false,
                Instant.now()
        );
    }

    private ChatChunk toChatChunk(OllamaChatResponse response) {
        if (response.done()) {
            return ChatChunk.terminal(usageOf(response), mapFinishReason(response.doneReason()));
        }
        return ChatChunk.of(response.message().content());
    }

    private static Usage usageOf(OllamaChatResponse response) {
        Integer promptTokens = response.promptEvalCount();
        Integer completionTokens = response.evalCount();
        return (promptTokens == null || completionTokens == null)
                ? Usage.estimated(0, 0)
                : Usage.exact(promptTokens, completionTokens);
    }

    private static FinishReason mapFinishReason(String doneReason) {
        return switch (doneReason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case null, default -> FinishReason.ERROR;
        };
    }

    private ProviderException mapError(Throwable error) {
        return classifyError(error);
    }
}