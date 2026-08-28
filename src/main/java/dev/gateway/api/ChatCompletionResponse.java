package dev.gateway.api;

import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<ChatCompletionChoice> choices,
        ApiUsage usage
) {}