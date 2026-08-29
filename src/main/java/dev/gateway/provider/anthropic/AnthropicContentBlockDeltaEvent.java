package dev.gateway.provider.anthropic;

/** Payload of the "content_block_delta" SSE event. */
record AnthropicContentBlockDeltaEvent(AnthropicTextDelta delta) {}