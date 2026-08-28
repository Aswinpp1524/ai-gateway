package dev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

record OpenAiUsage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens
) {}