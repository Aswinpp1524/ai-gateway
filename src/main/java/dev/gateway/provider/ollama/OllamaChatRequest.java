package dev.gateway.provider.ollama;

import java.util.List;

record


OllamaChatRequest(
        String model,
        List<OllamaMessage> messages,
        boolean stream,
        OllamaOptions options
) {}