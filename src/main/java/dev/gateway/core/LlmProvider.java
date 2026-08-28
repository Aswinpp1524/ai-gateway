package dev.gateway.core;

import dev.gateway.core.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LlmProvider {

    /** Stable identifier used in config, metrics, and usage records. */
    String name();

    /** Whether this provider can serve the requested model. */
    boolean supports(String model);

    Mono<ChatResponse> complete(ChatRequest request);

    Flux<ChatChunk> stream(ChatRequest request);
}