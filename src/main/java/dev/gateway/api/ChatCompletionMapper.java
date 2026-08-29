package dev.gateway.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Message;
import dev.gateway.core.model.Role;
import dev.gateway.core.model.Usage;

/** The only class that touches both dev.gateway.api and dev.gateway.core.model types. */
@Component
public class ChatCompletionMapper {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionMapper.class);

    public ChatRequest toCoreRequest(ChatCompletionRequest request) {
        List<Message> messages = request.messages().stream()
                .map(m -> new Message(Role.valueOf(m.role().toUpperCase()), m.content()))
                .toList();
        return new ChatRequest(
                request.model(), messages, request.temperature(), request.maxTokens(),
                Boolean.TRUE.equals(request.stream()));
    }

    public ChatCompletionResponse toApiResponse(ChatResponse response) {
        ChatMessage message = new ChatMessage("assistant", response.content());
        ChatCompletionChoice choice = new ChatCompletionChoice(0, message, toFinishReason(response.finishReason()));
        return new ChatCompletionResponse(
                withChatcmplPrefix(response.id()),
                "chat.completion",
                response.createdAt().getEpochSecond(),
                response.model(),
                List.of(choice),
                toApiUsage(response.usage()));
    }

    /** OpenAI's own ids already start with "chatcmpl-"; Anthropic's (msg_...) and Ollama's
     * (a generated UUID) don't, and still need it added. */
    private static String withChatcmplPrefix(String id) {
        return id.startsWith("chatcmpl-") ? id : "chatcmpl-" + id;
    }

    /**
     * A non-terminal ChatChunk maps to one delta frame. A terminal ChatChunk maps to TWO frames,
     * matching real OpenAI's include_usage streaming behaviour: a finish-reason frame (finish_reason
     * needs a choice to attach to) followed by a usage frame with an empty choices array.
     */
    public List<ChatCompletionChunk> toApiChunks(ChatChunk chunk, String id, long created, String model) {
        if (chunk.last()) {
            ChatCompletionChunk finishChunk = new ChatCompletionChunk(
                    id, "chat.completion.chunk", created, model,
                    List.of(new ChatCompletionChunkChoice(0, new ChatCompletionDelta(null), toFinishReason(chunk.finishReason()))),
                    null);
            ChatCompletionChunk usageChunk = new ChatCompletionChunk(
                    id, "chat.completion.chunk", created, model, List.of(), toApiUsage(chunk.usage()));
            return List.of(finishChunk, usageChunk);
        }
        ChatCompletionChunk deltaChunk = new ChatCompletionChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new ChatCompletionChunkChoice(0, new ChatCompletionDelta(chunk.delta()), null)),
                null);
        return List.of(deltaChunk);
    }

    private static ApiUsage toApiUsage(Usage usage) {
        return new ApiUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private static String toFinishReason(FinishReason reason) {
        if (reason == null) {
            return unmappedFinishReason(null);
        }
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case CANCELLED, ERROR -> unmappedFinishReason(reason);
        };
    }

    private static String unmappedFinishReason(FinishReason reason) {
        log.warn("finishReason={} has no OpenAI wire equivalent, or was missing entirely (e.g. a stream "
                + "that completed without a finish-reason frame); a provider error should have surfaced "
                + "as a ProviderException before this point. Mapping to \"stop\".", reason);
        return "stop";
    }
}