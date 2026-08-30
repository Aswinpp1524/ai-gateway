package dev.gateway.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.gateway.core.ProviderException;
import dev.gateway.core.cache.CacheResult;
import dev.gateway.core.cache.SemanticCache;
import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.Usage;
import dev.gateway.observability.GatewayMetrics;
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
    private static final String UNKNOWN_PROVIDER = "unknown";

    private final SemanticCache semanticCache;
    private final ChatCompletionMapper mapper;
    private final ObjectMapper objectMapper;
    private final GatewayMetrics gatewayMetrics;

    public ChatCompletionController(
            SemanticCache semanticCache, ChatCompletionMapper mapper, ObjectMapper objectMapper,
            GatewayMetrics gatewayMetrics) {
        this.semanticCache = semanticCache;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.gatewayMetrics = gatewayMetrics;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> createChatCompletion(@Valid @RequestBody ChatCompletionRequest request) {
        ChatRequest coreRequest = mapper.toCoreRequest(request);
        return Boolean.TRUE.equals(request.stream())
                ? streamResponse(coreRequest)
                : completeResponse(coreRequest);
    }

    private Mono<ResponseEntity<?>> completeResponse(ChatRequest coreRequest) {
        Instant start = Instant.now();
        return TenantContext.current()
                .flatMap(tenant -> {
                    String tenantId = tenant.id().toString();
                    return semanticCache.complete(coreRequest, tenant.id())
                            .doOnNext(result -> recordCompleteSuccess(tenantId, coreRequest.model(), start, result))
                            .doOnError(error -> recordFailure(tenantId, coreRequest.model(), start, error));
                })
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
        Instant start = Instant.now();
        return TenantContext.current()
                .flatMap(tenant -> {
                    String tenantId = tenant.id().toString();
                    return semanticCache.stream(coreRequest, tenant.id())
                            .map(result -> {
                                Flux<ServerSentEvent<String>> body = toSseFlux(
                                        result.chunks(), coreRequest.model(), tenantId, result.hit(), start);
                                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                                        .contentType(MediaType.TEXT_EVENT_STREAM)
                                        .header(CACHE_HEADER, cacheStatus(result.hit()));
                                if (result.hit()) {
                                    builder.header(CACHE_SIMILARITY_HEADER, String.valueOf(result.similarity()));
                                }
                                ResponseEntity<?> response = builder.body(body);
                                return response;
                            });
                });
    }

    private static String cacheStatus(boolean hit) {
        return hit ? "HIT" : "MISS";
    }

    private void recordCompleteSuccess(String tenantId, String model, Instant start, CacheResult result) {
        Duration total = Duration.between(start, Instant.now());
        Duration overhead = total.minus(result.providerLatency());
        String provider = result.response().servedBy();
        gatewayMetrics.recordRequest(tenantId, model, provider, result.hit(), "success", total, overhead);
        Usage usage = result.response().usage();
        gatewayMetrics.recordTokens(
                tenantId, model, provider, usage.estimated(), usage.promptTokens(), usage.completionTokens());
    }

    /** No overhead recorded here - a request that failed before producing a CacheResult never
     * gave us a clean providerLatency to subtract, and fabricating one would misrepresent what
     * the metric means. */
    private void recordFailure(String tenantId, String model, Instant start, Throwable error) {
        Duration total = Duration.between(start, Instant.now());
        String provider = (error instanceof ProviderException pe) ? pe.provider() : UNKNOWN_PROVIDER;
        gatewayMetrics.recordRequest(tenantId, model, provider, false, "error", total, null);
    }

    /**
     * Errors here can't go through ApiExceptionHandler - the 200 and headers are already on the
     * wire by the time a mid-stream failure happens, so the only way to signal it is an SSE frame
     * carrying the same ErrorResponse shape the advice returns, followed by [DONE] so clients
     * looping until the sentinel don't hang. Stream cancellation metrics live in
     * FallbackChainRouter, since it's the layer that knows which provider was actually in flight
     * when the client disconnected (a cache hit never reaches a provider, so there's nothing to
     * attribute a cancellation to there anyway).
     */
    private Flux<ServerSentEvent<String>> toSseFlux(
            Flux<ChatChunk> chunks, String model, String tenantId, boolean cacheHit, Instant start) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        AtomicReference<ChatChunk> terminal = new AtomicReference<>();

        return chunks
                .doOnNext(chunk -> {
                    if (chunk.last()) {
                        terminal.set(chunk);
                    }
                })
                .doOnComplete(() -> recordStreamSuccess(tenantId, model, cacheHit, start, terminal.get()))
                .doOnError(error -> recordFailure(tenantId, model, start, error))
                .concatMap(chunk -> Flux.fromIterable(mapper.toApiChunks(chunk, id, created, model)))
                .map(this::toEvent)
                .concatWithValues(DONE_EVENT)
                .onErrorResume(error -> Flux.just(toEvent(toErrorResponse(error)), DONE_EVENT));
    }

    /**
     * Overhead is only recorded on a cache hit, where it trivially equals total (no provider was
     * ever called). On a miss, the cache write happens as part of the same stream (appended via
     * concatWith in SemanticCache) after the provider's own streaming completes, so "time spent
     * on our code" and "time spent waiting on the provider" are entangled by the time this fires
     * - separating them honestly would need finer-grained instrumentation inside SemanticCache's
     * streaming path than exists today, so this skips overhead for misses rather than guess.
     */
    private void recordStreamSuccess(String tenantId, String model, boolean cacheHit, Instant start, ChatChunk terminal) {
        Duration total = Duration.between(start, Instant.now());
        String provider = terminal != null ? terminal.servedBy() : UNKNOWN_PROVIDER;
        Duration overhead = cacheHit ? total : null;
        gatewayMetrics.recordRequest(tenantId, model, provider, cacheHit, "success", total, overhead);
        if (terminal != null && terminal.usage() != null) {
            Usage usage = terminal.usage();
            gatewayMetrics.recordTokens(
                    tenantId, model, provider, usage.estimated(), usage.promptTokens(), usage.completionTokens());
        }
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
