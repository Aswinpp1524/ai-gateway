package dev.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatCompletionChoice(
        int index,
        ChatMessage message,
        @JsonProperty("finish_reason") String finishReason
) {}