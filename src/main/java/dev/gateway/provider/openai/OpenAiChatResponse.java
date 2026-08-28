package dev.gateway.provider.openai;

import java.util.List;

record OpenAiChatResponse(String id, String model, List<OpenAiChoice> choices, OpenAiUsage usage) {}