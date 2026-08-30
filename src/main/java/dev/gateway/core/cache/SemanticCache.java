package dev.gateway.core.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import dev.gateway.core.model.ChatChunk;
import dev.gateway.core.model.ChatRequest;
import dev.gateway.core.model.ChatResponse;
import dev.gateway.core.model.Message;
import dev.gateway.core.model.Role;
import dev.gateway.core.model.Usage;
import dev.gateway.core.router.FallbackChainRouter;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sits between the API layer and FallbackChainRouter: looks up a semantically similar prior
 * response before ever calling a provider, and writes new entries on a miss.
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final FallbackChainRouter router;
    private final CacheRepository repository;
    private final EmbeddingModel embeddingModel;
    private final CacheProperties properties;

    public SemanticCache(
            FallbackChainRouter router,
            CacheRepository repository,
            EmbeddingModel embeddingModel,
            CacheProperties properties) {
        this.router = router;
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public Mono<CacheResult> complete(ChatRequest request, UUID tenantId) {
        if (!properties.enabled() || !isCacheable(request)) {
            Instant callStart = Instant.now();
            return router.complete(request)
                    .map(response -> new CacheResult(
                            response, false, null, Duration.between(callStart, Instant.now())));
        }
        String text = embeddingText(request);
        return embed(text).flatMap(embedding -> {
            String literal = toVectorLiteral(embedding);
            return repository.findSimilar(tenantId, request.model(), literal, maxDistance())
                    .map(entry -> {
                        recordHit(entry.id());
                        return new CacheResult(toChatResponse(entry.payload()), true, entry.similarity(), Duration.ZERO);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        Instant callStart = Instant.now();
                        return router.complete(request)
                                .flatMap(response -> writeCacheEntry(tenantId, request, text, literal, response)
                                        .onErrorResume(e -> logAndSwallow("cache write", e))
                                        .thenReturn(new CacheResult(
                                                response, false, null, Duration.between(callStart, Instant.now()))));
                    }));
        });
    }

    public Mono<CacheStreamResult> stream(ChatRequest request, UUID tenantId) {
        if (!properties.enabled() || !isCacheable(request)) {
            return Mono.just(new CacheStreamResult(router.stream(request), false, null));
        }
        String text = embeddingText(request);
        return embed(text).flatMap(embedding -> {
            String literal = toVectorLiteral(embedding);
            return repository.findSimilar(tenantId, request.model(), literal, maxDistance())
                    .map(entry -> {
                        recordHit(entry.id());
                        return new CacheStreamResult(streamCachedPayload(entry.payload()), true, entry.similarity());
                    })
                    .switchIfEmpty(Mono.just(
                            new CacheStreamResult(streamAndWrite(request, tenantId, text, literal), false, null)));
        });
    }

    private Flux<ChatChunk> streamAndWrite(ChatRequest request, UUID tenantId, String text, String embeddingLiteral) {
        StringBuilder accumulated = new StringBuilder();
        AtomicReference<ChatChunk> terminal = new AtomicReference<>();
        return router.stream(request)
                .doOnNext(chunk -> {
                    if (chunk.last()) {
                        terminal.set(chunk);
                    } else {
                        accumulated.append(chunk.delta());
                    }
                })
                // Only reached after the upstream completes successfully - on error or client
                // cancellation, concatWith's second source is never subscribed, so a partial or
                // errored stream is never written to the cache. No manual success flag needed.
                .concatWith(Mono.defer(() -> {
                    ChatChunk last = terminal.get();
                    if (last == null) {
                        return Mono.<ChatChunk>empty();
                    }
                    CachedResponsePayload payload = new CachedResponsePayload(
                            request.model(), last.servedBy(), accumulated.toString(),
                            last.usage().promptTokens(), last.usage().completionTokens(), last.usage().estimated(),
                            last.finishReason());
                    Instant expiresAt = Instant.now().plus(properties.ttl());
                    return repository.insert(tenantId, request.model(), text, embeddingLiteral, payload, expiresAt)
                            .onErrorResume(e -> logAndSwallow("cache write", e))
                            .then(Mono.<ChatChunk>empty());
                }));
    }

    private Mono<Void> writeCacheEntry(
            UUID tenantId, ChatRequest request, String promptText, String embeddingLiteral, ChatResponse response) {
        CachedResponsePayload payload = new CachedResponsePayload(
                request.model(), response.servedBy(), response.content(),
                response.usage().promptTokens(), response.usage().completionTokens(), response.usage().estimated(),
                response.finishReason());
        Instant expiresAt = Instant.now().plus(properties.ttl());
        return repository.insert(tenantId, request.model(), promptText, embeddingLiteral, payload, expiresAt);
    }

    /** Fire-and-forget: hit_count is pure bookkeeping, and delaying an already-fast cache hit to
     * wait on it would undercut the entire point of caching. */
    private void recordHit(UUID entryId) {
        repository.incrementHitCount(entryId)
                .onErrorResume(e -> logAndSwallow("hit-count increment", e))
                .subscribe();
    }

    /**
     * Caching against an unset temperature assumes the provider's own default isn't the kind of
     * high-variety setting a caller would explicitly opt into - we don't actually control what
     * that default is, and most callers never set temperature at all, so excluding unset would
     * gut the cache's practical value. If this assumption ever causes visibly wrong cached
     * answers, the fix is defaulting temperature explicitly in ChatCompletionMapper, not
     * narrowing this check.
     */
    private boolean isCacheable(ChatRequest request) {
        List<Message> nonSystem = request.nonSystemMessages();
        boolean singleTurn = nonSystem.size() == 1 && nonSystem.get(0).role() == Role.USER;
        boolean lowTemperature =
                request.temperature() == null || request.temperature() <= properties.maxCacheableTemperature();
        return singleTurn && lowTemperature;
    }

    private double maxDistance() {
        return 1 - properties.similarityThreshold();
    }

    private Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> embeddingModel.embed(text)).subscribeOn(Schedulers.boundedElastic());
    }

    private static String embeddingText(ChatRequest request) {
        String userContent = request.nonSystemMessages().get(0).content();
        return request.systemPrompt().map(sp -> sp + "\n\n" + userContent).orElse(userContent);
    }

    private static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private static ChatResponse toChatResponse(CachedResponsePayload payload) {
        return new ChatResponse(
                UUID.randomUUID().toString(),
                payload.model(),
                payload.servedBy(),
                payload.content(),
                new Usage(payload.promptTokens(), payload.completionTokens(), payload.estimated()),
                payload.finishReason(),
                true,
                Instant.now());
    }

    /** Word-by-word so a cache hit still feels like a stream to a client that renders
     * progressively - splitting after each whitespace char keeps it byte-exact on concatenation. */
    private static Flux<ChatChunk> streamCachedPayload(CachedResponsePayload payload) {
        String[] words = payload.content().split("(?<=\\s)");
        ChatChunk terminal = ChatChunk.terminal(
                new Usage(payload.promptTokens(), payload.completionTokens(), payload.estimated()),
                payload.finishReason(), payload.servedBy());
        return Flux.fromArray(words).map(ChatChunk::of).concatWithValues(terminal);
    }

    private static <T> Mono<T> logAndSwallow(String action, Throwable error) {
        log.warn("{} failed", action, error);
        return Mono.empty();
    }
}
