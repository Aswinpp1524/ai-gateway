package dev.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunkChoice(
        int index,
        ChatCompletionDelta delta,
        @JsonProperty("finish_reason") String finishReason
) {}