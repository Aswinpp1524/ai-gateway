package dev.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiUsage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
) {}