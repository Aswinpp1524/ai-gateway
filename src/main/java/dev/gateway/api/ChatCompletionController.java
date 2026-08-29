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
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.router.FallbackChainRouter;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final ServerSentEvent<String> DONE_EVENT = ServerSentEvent.<String>builder("[DONE]").build();

    private final FallbackChainRouter router;
    private final ChatCompletionMapper mapper;
    private final ObjectMapper objectMapper;

    public ChatCompletionController(FallbackChainRouter router, ChatCompletionMapper mapper, ObjectMapper objectMapper) {
        this.router = router;
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
        return router.complete(coreRequest).map(mapper::toApiResponse).map(chatResponse -> {
            ResponseEntity<?> response = ResponseEntity.ok(chatResponse);
            return response;
        });
    }

    private Mono<ResponseEntity<?>> streamResponse(ChatRequest coreRequest) {
        Flux<ServerSentEvent<String>> body = streamChatCompletion(coreRequest);
        ResponseEntity<?> response = ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
        return Mono.just(response);
    }

    /**
     * Errors here can't go through ApiExceptionHandler - the 200 and headers are already on the
     * wire by the time a mid-stream failure happens, so the only way to signal it is an SSE frame
     * carrying the same ErrorResponse shape the advice returns, followed by [DONE] so clients
     * looping until the sentinel don't hang. Cancellation logging lives in FallbackChainRouter now,
     * since it's the layer that knows which provider was actually in flight.
     */
    private Flux<ServerSentEvent<String>> streamChatCompletion(ChatRequest coreRequest) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();

        return router.stream(coreRequest)
                .concatMap(chunk -> Flux.fromIterable(mapper.toApiChunks(chunk, id, created, coreRequest.model())))
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