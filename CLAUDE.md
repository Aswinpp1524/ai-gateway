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

- Jackson 3: imports are `tools.jackson.*`, not `com.fasterxml.jackson.*`.
- Testcontainers 2.x database modules aren't published; tests use the
  docker-compose stack or WireMock rather than containers.
- Ollama runs natively on the host at localhost:11434, not in compose.
- Embedding dimension is 768 (nomic-embed-text) and is baked into the schema.

## Local development

`docker compose up -d` starts Postgres, Redis, Prometheus, Grafana.
`set -a && source .env && set +a` loads provider API keys.
`mvn spring-boot:run` starts the gateway on 8080.

## Working style

Propose the design before writing code for anything structural — show the
options and trade-offs, and let me choose. Prefer small vertical slices over
large layer-at-a-time changes. Explain reasoning for non-obvious decisions;
I need to be able to defend every line of this in an interview.