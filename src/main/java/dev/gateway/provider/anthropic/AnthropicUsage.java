package dev.gateway.provider.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;

record AnthropicUsage(
        @JsonProperty("input_tokens") Integer inputTokens,
        @JsonProperty("output_tokens") Integer outputTokens
) {}