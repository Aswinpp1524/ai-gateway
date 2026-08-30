package dev.gateway.core.cache;

import dev.gateway.core.model.FinishReason;

/**
 * What actually gets stored in cache_entries.response_json. Deliberately NOT the full
 * ChatResponse - id and createdAt belong to a particular HTTP response, not to the cached
 * content, so a fresh id/createdAt is synthesized on every serve (see SemanticCache).
 */
record CachedResponsePayload(
        String model,
        String servedBy,
        String content,
        int promptTokens,
        int completionTokens,
        boolean estimated,
        FinishReason finishReason
) {}
