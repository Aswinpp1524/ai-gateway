package dev.gateway.provider.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenAiChatRequest(
        String model,
        List<OpenAiMessage> messages,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        boolean stream,
        @JsonProperty("stream_options") OpenAiStreamOptions streamOptions
) {}