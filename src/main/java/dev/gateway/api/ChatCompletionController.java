package dev.gateway.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.gateway.core.ProviderException;
import dev.gateway.core.cache.SemanticCache;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.tenant.TenantContext;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final ServerSentEvent<String> DONE_EVENT = ServerSentEvent.<String>builder("[DONE]").build();
    private static final String CACHE_HEADER = "X-Cache";
    private static final String CACHE_SIMILARITY_HEADER = "X-Cache-Similarity";

    private final SemanticCache semanticCache;
    private final ChatCompletionMapper mapper;
    private final ObjectMapper objectMapper;

    public ChatCompletionController(SemanticCache semanticCache, ChatCompletionMapper mapper, ObjectMapper objectMapper) {
        this.semanticCache = semanticCache;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> createChatCompletion(@Valid @RequestBody ChatCompletionRequest request) {
        ChatRequest coreRequest = mapper.toCoreRequest(request);
        return Boolean.TRUE.equals(request.stream())
                ? streamResponse(coreRequest)
                : completeResponse(coreRequest);
    }

    private Mono<ResponseEntity<?>> completeResponse(ChatRequest coreRequest) {
        return TenantContext.current()
                .flatMap(tenant -> semanticCache.complete(coreRequest, tenant.id()))
                .map(result -> {
                    ChatCompletionResponse body = mapper.toApiResponse(result.response());
                    ResponseEntity.BodyBuilder builder =
                            ResponseEntity.ok().header(CACHE_HEADER, cacheStatus(result.hit()));
                    if (result.hit()) {
                        builder.header(CACHE_SIMILARITY_HEADER, String.valueOf(result.similarity()));
                    }
                    ResponseEntity<?> response = builder.body(body);
                    return response;
                });
    }

    private Mono<ResponseEntity<?>> streamResponse(ChatRequest coreRequest) {
        return TenantContext.current()
                .flatMap(tenant -> semanticCache.stream(coreRequest, tenant.id()))
                .map(result -> {
                    Flux<ServerSentEvent<String>> body = toSseFlux(result.chunks(), coreRequest.model());
                    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .header(CACHE_HEADER, cacheStatus(result.hit()));
                    if (result.hit()) {
                        builder.header(CACHE_SIMILARITY_HEADER, String.valueOf(result.similarity()));
                    }
                    ResponseEntity<?> response = builder.body(body);
                    return response;
                });
    }

    private static String cacheStatus(boolean hit) {
        return hit ? "HIT" : "MISS";
    }

    /**
     * Errors here can't go through ApiExceptionHandler - the 200 and headers are already on the
     * wire by the time a mid-stream failure happens, so the only way to signal it is an SSE frame
     * carrying the same ErrorResponse shape the advice returns, followed by [DONE] so clients
     * looping until the sentinel don't hang. Cancellation logging lives in FallbackChainRouter,
     * since it's the layer that knows which provider was actually in flight (a cache hit never
     * reaches a provider at all, so there's nothing to log there in that case).
     */
    private Flux<ServerSentEvent<String>> toSseFlux(Flux<ChatChunk> chunks, String model) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();

        return chunks
                .concatMap(chunk -> Flux.fromIterable(mapper.toApiChunks(chunk, id, created, model)))
                .map(this::toEvent)
                .concatWithValues(DONE_EVENT)
                .onErrorResume(error -> Flux.just(toEvent(toErrorResponse(error)), DONE_EVENT));
    }

    private ServerSentEvent<String> toEvent(Object payload) {
        return ServerSentEvent.<String>builder(objectMapper.writeValueAsString(payload)).build();
    }

    private static ErrorResponse toErrorResponse(Throwable error) {
        if (error instanceof ProviderException providerException) {
            return new ErrorResponse(providerException.getMessage(), "provider_error");
        }
        return new ErrorResponse("Internal error during streaming", "internal_error");
    }
}
