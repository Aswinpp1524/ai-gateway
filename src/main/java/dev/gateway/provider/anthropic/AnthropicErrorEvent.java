package dev.gateway.provider.anthropic;

/** Payload of the "error" SSE event - a provider-side failure signalled mid-stream. */
record AnthropicErrorEvent(AnthropicErrorDetail error) {}