package dev.gateway.provider.ollama;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record OllamaChatRequest(
        String model,
        List<OllamaMessage> messages,
        boolean stream,
        OllamaOptions options
) {}