package dev.gateway.provider.openai;

import java.util.List;

/**
 * Shape of one decoded SSE "data:" payload. The finish_reason (on a choice frame) and usage
 * (on a separate, later frame with empty choices) arrive on different frames - see
 * OpenAiProvider.toChatChunk for how they're recombined into a single terminal ChatChunk.
 */
record OpenAiChatChunk(List<OpenAiChoiceDelta> choices, OpenAiUsage usage) {}