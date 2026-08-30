# AI Gateway

A multi-provider LLM gateway built in Spring WebFlux. It sits between client
applications and LLM providers, handling routing, failover, semantic caching,
per-tenant rate limiting, cost metering, and observability.

The point of the project is that LLM calls are extreme I/O-bound work: wall
time exceeds CPU time by roughly 1000x. Every design decision follows from
that.

## Architecture

Request path:
api → tenant filters → semantic cache → router → provider adapters → providers

Metrics and tracing are cross-cutting concerns present at every layer.

Packages under `dev.gateway`:
- `api` — controllers and DTOs in OpenAI wire format
- `core` — LlmProvider interface, AbstractLlmProvider, exception hierarchy
- `core/model` — vendor-neutral domain types (ChatRequest, ChatResponse, Usage)
- `core/router` — model selection and fallback chain
- `core/cache` — semantic cache
- `core/resilience` — circuit breaker, retry, timeout wrappers
- `provider/{openai,anthropic,ollama}` — vendor adapters
- `tenant` — API key auth, rate limiting
- `metering` — token counting, cost calculation, budgets
- `observability` — metrics and tracing setup
- `persistence` — repositories

## Rules

- Fully reactive. Never call `.block()`. No blocking I/O on event loop threads.
- `core` must never import from `provider`. Dependencies point inward.
- Provider adapters expose two methods: `complete()` returning
  `Mono<ChatResponse>` and `stream()` returning `Flux<ChatChunk>`. Request
  building and error mapping are shared private helpers called by both —
  never duplicated.
- Vendor wire DTOs stay package-private inside their provider package. They
  must never be visible to `core` or `api`.
- Every provider call goes through the resilience wrapper. No direct WebClient
  calls from routing code.
- Money is stored as integer micros, never floating point.
- Tenant isolation is a hard requirement. Cache lookups and usage records are
  always scoped by tenant_id. A cache entry from one tenant must never be
  served to another.
- Config over constants. Thresholds, fallback order, and timeouts live in
  application.yml, not hardcoded.
- API keys are stored hashed, never in plaintext.
- Constructor injection only. No field injection.

## Stack

Java 21, Spring Boot 4.1.1, Spring Framework 7, WebFlux, Resilience4j,
Redis (Lettuce reactive), Postgres 17 + pgvector via R2DBC, Spring AI 2.0
(embeddings only), Micrometer + Prometheus + Grafana, Ollama for the local
model.

## Environment gotchas

- Jackson 3: jackson-core and jackson-databind moved to `tools.jackson.*`,
  but jackson-annotations kept the old `com.fasterxml.jackson.annotation`
  namespace. So JsonMapper is `tools.jackson.databind.json.JsonMapper` but
  `@JsonProperty` is still `com.fasterxml.jackson.annotation.JsonProperty`.
- Testcontainers 2.x database modules aren't published to Maven Central;
  tests use the docker-compose stack or WireMock rather than containers.
- Ollama runs natively on the host at localhost:11434, not in compose.
  There are two ollama config blocks: `spring.ai.ollama` for embeddings
  (Day 3) and `gateway.providers.ollama` for the chat adapter.
- Embedding dimension is 768 (nomic-embed-text) and is baked into the schema.
- Ollama streams newline-delimited JSON, not SSE. OpenAI and Anthropic use
  real SSE frames with a `data:` prefix. The adapters parse differently.
- Retryable and failover-eligible are different predicates.
  CallNotPermittedException (open circuit) is failover-eligible but not
  retry-eligible - retrying into an open circuit is pointless.
- Streaming failover only applies before the first chunk reaches the client.
  Once partial output is delivered, any retry would produce garbled text.
  Resilience4j's RetryOperator can't be used on a Flux for this reason -
  it resubscribes the whole stream.
- Postgres.app runs a native server on 5432 and shadows the Docker container
  (a specific localhost bind beats Docker's wildcard). The gateway container
  is mapped to 5433 for this reason.

## Local development

`docker compose up -d` starts Postgres, Redis, Prometheus, Grafana.
`set -a && source .env && set +a` loads provider API keys.
`mvn spring-boot:run` starts the gateway on 8080.

## Working style

Propose the design before writing code for anything structural — show the
options and trade-offs, and let me choose. Prefer small vertical slices over
large layer-at-a-time changes. Explain reasoning for non-obvious decisions;
I need to be able to defend every line of this in an interview.

Verify environment facts against the actual jars, docs, or a running service
rather than relying on memory. Version details in this file have already been
wrong once.