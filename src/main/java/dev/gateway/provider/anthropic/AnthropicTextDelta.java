package dev.gateway.provider.anthropic;

/** type is expected to be "text_delta"; only text is used. */
record AnthropicTextDelta(String type, String text) {}