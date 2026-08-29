package dev.gateway.provider.anthropic;

/** Payload of the "message_delta" SSE event. Carries stop_reason and output_tokens together. */
record AnthropicMessageDeltaEvent(AnthropicStopReason delta, AnthropicUsage usage) {}