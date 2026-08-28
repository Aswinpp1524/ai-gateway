package dev.gateway.core.model;

import java.util.List;
import java.util.Optional;

public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer maxTokens,
        boolean stream
) {
    public ChatRequest {
        messages = List.copyOf(messages);
    }

    /**
     * Anthropic takes the system prompt as a top-level field rather than
     * a message. Adapters that need it hoisted use this.
     */
    public Optional<String> systemPrompt() {
        return messages.stream()
                .filter(m -> m.role() == Role.SYSTEM)
                .map(Message::content)
                .findFirst();
    }

    public List<Message> nonSystemMessages() {
        return messages.stream()
                .filter(m -> m.role() != Role.SYSTEM)
                .toList();
    }
}