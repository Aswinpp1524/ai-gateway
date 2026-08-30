package dev.gateway.provider.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import dev.gateway.core.AbstractLlmProvider;
import dev.gateway.core.ProviderException;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Usage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "gateway.providers.openai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenAiProvider extends AbstractLlmProvider {

    private static final String CHAT_ENDPOINT = "/v1/chat/completions";
    private static final String DONE_SENTINEL = "[DONE]";

    private final OpenAiProviderProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(OpenAiProviderProperties properties, WebClient.Builder builder, ObjectMapper objectMapper) {
        super(builder, properties.baseUrl(), Duration.ofSeconds(properties.timeoutSeconds()));
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean supports(String model) {
        return properties.models().contains(model);
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        OpenAiChatRequest body = buildRequestBody(request, false);
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAiChatResponse.class)
                .map(this::toChatResponse)
                .onErrorMap(this::mapError);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        OpenAiChatRequest body = buildRequestBody(request, true);
        AtomicReference<FinishReason> pendingFinishReason = new AtomicReference<>();
        AtomicBoolean terminalEmitted = new AtomicBoolean(false);

        Flux<ChatChunk> chunks = webClient.post()
                .uri(CHAT_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !DONE_SENTINEL.equals(data))
                .map(data -> objectMapper.readValue(data, OpenAiChatChunk.class))
                .flatMap(chunk -> toChatChunk(chunk, pendingFinishReason, terminalEmitted));

        return chunks
                .concatWith(Mono.defer(() -> terminalEmitted.get()
                        ? Mono.empty()
                        : Mono.just(ChatChunk.terminal(Usage.estimated(0, 0), pendingFinishReason.get(), name()))))
                .onErrorMap(this::mapError);
    }

    private OpenAiChatRequest buildRequestBody(ChatRequest request, boolean stream) {
        List<OpenAiMessage> messages = request.messages().stream()
                .map(m -> new OpenAiMessage(m.role().name().toLowerCase(), m.content()))
                .toList();
        OpenAiStreamOptions streamOptions = stream ? new OpenAiStreamOptions(true) : null;
        return new OpenAiChatRequest(
                request.model(), messages, request.temperature(), request.maxTokens(), stream, streamOptions);
    }

    private ChatResponse toChatResponse(OpenAiChatResponse response) {
        OpenAiChoice choice = response.choices().get(0);
        return new ChatResponse(
                response.id(),
                response.model(),
                name(),
                choice.message().content(),
                usageOf(response.usage()),
                mapFinishReason(choice.finishReason()),
                false,
                Instant.now());
    }

    /**
     * finish_reason and usage arrive on separate SSE frames (finish frame has real choices;
     * usage frame has an empty choices array), so finish_reason is carried across frames via
     * pendingFinishReason and combined with usage once the usage frame appears.
     */
    private Mono<ChatChunk> toChatChunk(
            OpenAiChatChunk chunk, AtomicReference<FinishReason> pendingFinishReason, AtomicBoolean terminalEmitted) {
        if (chunk.usage() != null) {
            terminalEmitted.set(true);
            return Mono.just(ChatChunk.terminal(usageOf(chunk.usage()), pendingFinishReason.get(), name()));
        }
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return Mono.empty();
        }
        OpenAiChoiceDelta choice = chunk.choices().get(0);
        if (choice.finishReason() != null) {
            pendingFinishReason.set(mapFinishReason(choice.finishReason()));
        }
        String content = choice.delta() != null ? choice.delta().content() : null;
        return (content != null && !content.isEmpty()) ? Mono.just(ChatChunk.of(content)) : Mono.empty();
    }

    private static Usage usageOf(OpenAiUsage usage) {
        if (usage == null || usage.promptTokens() == null || usage.completionTokens() == null) {
            return Usage.estimated(0, 0);
        }
        return Usage.exact(usage.promptTokens(), usage.completionTokens());
    }

    private static FinishReason mapFinishReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case null, default -> FinishReason.ERROR;
        };
    }

    private ProviderException mapError(Throwable error) {
        return classifyError(error);
    }

    /**
     * OpenAI reuses HTTP 429 for two different failures: a transient rate limit (retryable) and
     * an exhausted quota (won't recover on retry, and retrying it just slows down the Day 2
     * fallback chain). The two are distinguished only by error.type in the response body, not by
     * status code, so this overrides the default status-only classification for that one case.
     */
    @Override
    protected boolean isRetryable(int status, WebClientResponseException responseException) {
        if (status == 429 && "insufficient_quota".equals(errorType(responseException))) {
            return false;
        }
        return super.isRetryable(status, responseException);
    }

    private String errorType(WebClientResponseException responseException) {
        try {
            OpenAiErrorBody body = objectMapper.readValue(responseException.getResponseBodyAsString(), OpenAiErrorBody.class);
            return body.error() != null ? body.error().type() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}