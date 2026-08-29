package dev.gateway.provider.anthropic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import dev.gateway.core.AbstractLlmProvider;
import dev.gateway.core.ProviderException;
import dev.gateway.core.RetryableProviderException;
import dev.gateway.core.TerminalProviderException;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Usage;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "gateway.providers.anthropic", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnthropicProvider extends AbstractLlmProvider {

    private static final String MESSAGES_ENDPOINT = "/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicProviderProperties properties;
    private final ObjectMapper objectMapper;

    public AnthropicProvider(AnthropicProviderProperties properties, WebClient.Builder builder, ObjectMapper objectMapper) {
        super(builder, properties.baseUrl(), Duration.ofSeconds(properties.timeoutSeconds()));
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public boolean supports(String model) {
        return properties.models().contains(model);
    }

    @Override
    public Mono<ChatResponse> complete(ChatRequest request) {
        AnthropicChatRequest body = buildRequestBody(request, false);
        return webClient.post()
                .uri(MESSAGES_ENDPOINT)
                .header("x-api-key", properties.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(AnthropicChatResponse.class)
                .map(this::toChatResponse)
                .onErrorMap(this::mapError);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        AnthropicChatRequest body = buildRequestBody(request, true);
        AtomicReference<Integer> pendingInputTokens = new AtomicReference<>();
        AtomicBoolean terminalEmitted = new AtomicBoolean(false);

        Flux<ChatChunk> chunks = webClient.post()
                .uri(MESSAGES_ENDPOINT)
                .header("x-api-key", properties.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .flatMap(sse -> toChatChunk(sse, pendingInputTokens, terminalEmitted));

        return chunks
                .concatWith(Mono.defer(() -> terminalEmitted.get()
                        ? Mono.empty()
                        : Mono.just(ChatChunk.terminal(Usage.estimated(0, 0), FinishReason.ERROR))))
                .onErrorMap(this::mapError);
    }

    private AnthropicChatRequest buildRequestBody(ChatRequest request, boolean stream) {
        List<AnthropicMessage> messages = request.nonSystemMessages().stream()
                .map(m -> new AnthropicMessage(m.role().name().toLowerCase(), m.content()))
                .toList();
        List<AnthropicContentBlock> system = request.systemPrompt()
                .map(prompt -> List.of(new AnthropicContentBlock("text", prompt)))
                .orElse(null);
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.defaultMaxTokens();
        return new AnthropicChatRequest(
                request.model(),
                system,
                messages,
                maxTokens,
                request.temperature(),
                stream);
    }

    private ChatResponse toChatResponse(AnthropicChatResponse response) {
        String content = response.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(AnthropicContentBlock::text)
                .collect(Collectors.joining());
        return new ChatResponse(
                response.id(),
                response.model(),
                name(),
                content,
                usageOf(response.usage()),
                mapStopReason(response.stopReason()),
                false,
                Instant.now());
    }

    /**
     * Anthropic splits input_tokens (message_start) from output_tokens/stop_reason
     * (message_delta), and sends a named "error" event mid-stream on provider-side failures.
     * That must surface as an error signal here, not a dropped frame - a silently truncated
     * stream would look like a normal completion to the Day 2 fallback chain.
     */
    private Mono<ChatChunk> toChatChunk(
            ServerSentEvent<String> sse, AtomicReference<Integer> pendingInputTokens, AtomicBoolean terminalEmitted) {
        if (sse.event() == null || sse.data() == null) {
            return Mono.empty();
        }
        return switch (sse.event()) {
            case "message_start" -> {
                AnthropicMessageStartEvent event = objectMapper.readValue(sse.data(), AnthropicMessageStartEvent.class);
                pendingInputTokens.set(event.message() != null && event.message().usage() != null
                        ? event.message().usage().inputTokens() : null);
                yield Mono.empty();
            }
            case "content_block_delta" -> {
                AnthropicContentBlockDeltaEvent event =
                        objectMapper.readValue(sse.data(), AnthropicContentBlockDeltaEvent.class);
                String text = event.delta() != null ? event.delta().text() : null;
                yield (text != null && !text.isEmpty()) ? Mono.just(ChatChunk.of(text)) : Mono.empty();
            }
            case "message_delta" -> {
                AnthropicMessageDeltaEvent event = objectMapper.readValue(sse.data(), AnthropicMessageDeltaEvent.class);
                terminalEmitted.set(true);
                Usage usage = usageOf(pendingInputTokens.get(),
                        event.usage() != null ? event.usage().outputTokens() : null);
                FinishReason reason = mapStopReason(event.delta() != null ? event.delta().stopReason() : null);
                yield Mono.just(ChatChunk.terminal(usage, reason));
            }
            case "error" -> {
                AnthropicErrorEvent event = objectMapper.readValue(sse.data(), AnthropicErrorEvent.class);
                yield Mono.error(toStreamError(event));
            }
            default -> Mono.empty();
        };
    }

    /** overloaded_error/api_error are transient provider-side failures; everything else (bad request shape,
     * auth, etc) won't recover on retry. */
    private ProviderException toStreamError(AnthropicErrorEvent event) {
        String type = event.error() != null ? event.error().type() : null;
        String detail = event.error() != null ? event.error().message() : null;
        String message = "Provider stream error"
                + (type != null ? " (" + type + ")" : "")
                + (detail != null ? ": " + detail : "");
        boolean retryable = "overloaded_error".equals(type) || "api_error".equals(type);
        return retryable
                ? new RetryableProviderException(name(), message, null)
                : new TerminalProviderException(name(), message, null);
    }

    private static Usage usageOf(AnthropicUsage usage) {
        return usageOf(usage != null ? usage.inputTokens() : null, usage != null ? usage.outputTokens() : null);
    }

    private static Usage usageOf(Integer inputTokens, Integer outputTokens) {
        return (inputTokens == null || outputTokens == null)
                ? Usage.estimated(0, 0)
                : Usage.exact(inputTokens, outputTokens);
    }

    private static FinishReason mapStopReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn" -> FinishReason.STOP;
            case "max_tokens" -> FinishReason.LENGTH;
            case null, default -> FinishReason.ERROR;
        };
    }

    private ProviderException mapError(Throwable error) {
        return classifyError(error);
    }
}