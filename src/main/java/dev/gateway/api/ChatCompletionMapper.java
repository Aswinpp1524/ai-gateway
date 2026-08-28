package dev.gateway.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.FinishReason;
import dev.gateway.core.model.Message;
import dev.gateway.core.model.Role;

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
        ApiUsage usage = new ApiUsage(
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.usage().totalTokens());
        return new ChatCompletionResponse(
                "chatcmpl-" + response.id(),
                "chat.completion",
                response.createdAt().getEpochSecond(),
                response.model(),
                List.of(choice),
                usage);
    }

    private static String toFinishReason(FinishReason reason) {
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case CONTENT_FILTER -> "content_filter";
            case CANCELLED, ERROR -> {
                log.warn("ChatResponse reached the mapper with finishReason={}, which has no OpenAI wire "
                        + "equivalent; a provider error should have surfaced as a ProviderException before "
                        + "this point. Mapping to \"stop\".", reason);
                yield "stop";
            }
        };
    }
}