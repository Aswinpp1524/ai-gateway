package dev.gateway.provider.anthropic;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
record AnthropicChatRequest(
        String model,
        List<AnthropicContentBlock> system,
        List<AnthropicMessage> messages,
        @JsonProperty("max_tokens") int maxTokens,
        Double temperature,
        boolean stream
) {}