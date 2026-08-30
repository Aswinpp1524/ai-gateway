package dev.gateway.core.cache;

import dev.gateway.core.model.ChatChunk;

import reactor.core.publisher.Flux;

public record CacheStreamResult(Flux<ChatChunk> chunks, boolean hit, Double similarity) {}
