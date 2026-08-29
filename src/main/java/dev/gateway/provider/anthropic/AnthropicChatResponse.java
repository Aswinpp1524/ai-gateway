package dev.gateway.provider.anthropic;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

record AnthropicChatResponse(
        String id,
        String model,
        List<AnthropicContentBlock> content,
        @JsonProperty("stop_reason") String stopReason,
        AnthropicUsage usage
) {}