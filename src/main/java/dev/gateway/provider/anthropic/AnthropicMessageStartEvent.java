package dev.gateway.provider.anthropic;

/** Payload of the "message_start" SSE event. Carries input_tokens (nested under message.usage). */
record AnthropicMessageStartEvent(AnthropicStartMessage message) {}