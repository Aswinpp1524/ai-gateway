# Load testing

k6 scripts for four scenarios: gateway overhead in isolation, realistic end-to-end against
Ollama, cache effectiveness, and rate limiting under real concurrency. Each one runs with a
single `make` target and prints a markdown summary you can paste straight into the project
README.

## Prerequisites

- [k6](https://k6.io/) on your PATH (`brew install k6`).
- `docker compose up -d` for Postgres, Redis, Prometheus, Grafana.
- Ollama running natively on the host (`localhost:11434`) for scenarios 2 and 3 - it's not in
  docker compose (see CLAUDE.md's environment gotchas).
- The gateway itself running (`mvn spring-boot:run`), with the loadtest profile active for
  scenario 1: `mvn spring-boot:run -Dspring-boot.run.profiles=loadtest`.

## Auth

Every request in every scenario carries `Authorization: Bearer <tenant_api_key>` -
`load/lib/http.js` is the one place that sets it, so there's nowhere for a scenario to
accidentally send an unauthenticated request. Three tenants are seeded in `docker/init.sql`,
each scoped to what actually needs it:

| tenant | key | rpm | used by |
|---|---|---|---|
| `demo-tenant` | `gw_demo_key_12345` | 60 | general dev use, not the load test scenarios |
| `loadtest-tenant` | `gw_loadtest_key` | 1,000,000 | scenarios 1-3 |
| `loadtest-ratelimit-tenant` | `gw_loadtest_ratelimit_key` | 20 | scenario 4 only |

Scenarios 1-3 deliberately push far more concurrency than `demo-tenant`'s realistic 60 rpm would
survive - confirmed live: an initial pass at scenario 1 against `demo-tenant` came back with a
99.98% error rate that turned out to be `RateLimitFilter` returning 429 for nearly everything,
not a gateway problem. `loadtest-tenant` exists so these three scenarios measure what they're
named for instead of the rate limiter. `loadtest-ratelimit-tenant` stays separate from both so
scenario 4 tripping its limit doesn't leave anything else's bucket drained.

If your Postgres volume was initialized before these tenants existed in `init.sql`, seed them
once with `make seed-tenants` (idempotent - safe to re-run).

Run scenarios one at a time, not concurrently - scenario 4 hammering the rate limiter and
scenario 3's `setup()` warming Ollama don't play well with something else fighting for the same
resources at the same time.

## Scenarios

### 1. Gateway overhead (`make overhead`)

The headline number. Targets the stub provider (`dev.gateway.provider.stub`), which does no I/O
and returns immediately, so this measures auth, rate limiting, budget enforcement, routing,
resilience wrapping, and metering - the gateway's own code, not a vendor's network. Disabled by
default; requires `-Dspring-boot.run.profiles=loadtest`.

Requests use `temperature: 0.7`, above `gateway.cache.max-cacheable-temperature` (0.3), so every
request is genuinely routed to the provider - a cacheable request would hit the cache after the
first call and this scenario would end up measuring the cache path instead of the one it's named
for.

| stage | duration | target VUs |
|---|---|---|
| ramp up | 30s | 50 |
| ramp up | 1m | 200 |
| plateau | 2m | 200 |
| ramp down | 30s | 0 |

~4 minutes total. Reports throughput, p50/p95/p99, error rate.

**Metering-path verification.** `MeteringService.record()` writes the usage_log row and the
Redis budget-spend increment as fire-and-forget (`.subscribe()`, not awaited) - deliberately, so
a slow Postgres or Redis round trip never adds to a request's latency. That also means a failure
there is caught and logged, not surfaced to the client or to this load test - a silent bottleneck
is genuinely silent unless you go looking. After running this scenario:

1. Grep the gateway's log for the two messages `MeteringService` logs on failure:
   ```
   grep -E '"(usage_log write|budget spend update)" failed' <gateway log output>
   ```
   Any hits mean writes are failing under load - the exception logged alongside tells you
   whether it's the R2DBC pool (`spring.r2dbc.pool.*` isn't configured in `application.yml`, so
   Spring Boot's default pool size applies - small relative to 200 concurrent VUs) or Redis.
2. Compare row count against the scenario's reported successful-request count (printed in the
   summary as "successful (2xx) requests"):
   ```
   docker exec gw-postgres psql -U gateway -d gateway -c \
     "SELECT count(*) FROM usage_log WHERE served_by = 'stub' AND created_at >= now() - interval '10 minutes';"
   ```
   A count lower than the reported successful-request total means rows are being dropped - the
   metering path is the bottleneck at this concurrency, exactly what this addition to the
   scenario is meant to catch.

### 2. Realistic end-to-end (`make e2e`)

Targets `llama3.2` on Ollama. Same `temperature: 0.7` cache bypass as scenario 1, so the two
scenarios measure the same kind of request on the same footing.

"Moderate load" here is hardware-relative, and defaults to fully sequential (1 VU, `E2E_VUS`) -
confirmed live on this machine that Ollama serializes inference almost completely (5 concurrent
requests fired directly at it ranged from 6.8s to 88s+, not the ~7s each takes alone), and that
even 3 concurrent VUs was enough to push calls past the gateway's 30s resilience timeout, trigger
retries that piled on *more* competing load without cancelling the original request server-side,
and trip the `ollama` circuit breaker open - after which nearly every request got an instant
`503 circuit open` instead of a real response. That run reported 360 req/s at p50 2.2ms with a
99.98% error rate for the entire two minutes - a plausible-looking result that was actually
measuring resilience/circuit-breaker behavior under overload, not end-to-end latency.

| stage | duration | target VUs |
|---|---|---|
| ramp up | 15s | 1 (`E2E_VUS`) |
| plateau | 90s | 1 (`E2E_VUS`) |
| ramp down | 15s | 0 |

If you have GPU-backed Ollama or a beefier box, you can raise `E2E_VUS` above 1 - but watch for
`"circuit open"` in response bodies, or a p50/p95 that drops sharply while the error rate
spikes. Either is the sign you've gone past your Ollama's real concurrent capacity, not a
gateway problem: back `E2E_VUS` off and re-run.

Reports the same four numbers. This script does not compute "gateway overhead" itself - there's
no way to see the split between our code and Ollama's inference time from the client side.
Instead, put this scenario's p50/p95 next to scenario 1's in your README:

| | p50 | p95 |
|---|---|---|
| scenario 1 (gateway overhead only) | _fill in_ | _fill in_ |
| scenario 2 (end-to-end, Ollama) | _fill in_ | _fill in_ |
| **estimated provider-only latency** | *scenario 2 − scenario 1* | *scenario 2 − scenario 1* |

### 3. Cache effectiveness (`make cache`)

`setup()` warms a pool of 20 canonical prompts against Ollama (one request each, so they're
guaranteed cached before the timed run starts). During the run, each iteration either replays a
warmed prompt (expected `HIT`) or mints a brand-new one (expected `MISS`), at an 85% repeat rate
by default (`REPEAT_PCT`). The gateway's `X-Cache` response header is what actually classifies
each request, not which branch the script took, so a wrong assumption about cacheability would
show up as a hit ratio that doesn't match the target rather than being silently hidden.

No temperature override here (unlike scenarios 1 and 2) - these prompts are meant to be
cacheable.

A `MISS` here is a real Ollama call, same hardware-dependent constraint as scenario 2 - and
every VU can independently roll a miss on any iteration, so total concurrent Ollama load is
probabilistic rather than fixed at a set number. Constant 5 VUs (`CACHE_VUS`) and an 85% repeat
rate together keep expected concurrent misses well under 1. Raise `CACHE_VUS` or lower
`REPEAT_PCT` if your Ollama can take more; watch for the same circuit-breaker signs called out
in scenario 2 if you do.

3 minutes (long enough for a stable hit-ratio sample even with few misses). Reports the usual
four numbers plus: target vs. observed hit ratio, and separate p50/p95 for cache hits vs.
misses.

### 4. Rate limiting (`make ratelimit`)

10 VUs, no sleep, for 15 seconds, against `loadtest-ratelimit-tenant` (20 rpm). A burst like that
burns through the bucket almost immediately and keeps tripping it for the rest of the run.

Targets the stub provider - requires `-Dspring-boot.run.profiles=loadtest`, same as scenario 1.
`RateLimitFilter` runs before routing, so a *rejected* request never reaches routing either way,
but confirmed live that targeting `llama3.2` here produced one completed iteration in 45
seconds: the bucket starts full, so most of the first wave of requests get *allowed* through to
a real ~27s Ollama call, and the scenario runs out of time before the bucket is ever actually
exercised. The stub's near-zero latency is what makes 10 VUs able to burn through 20 tokens and
keep looping fast enough to see a stream of 429s within 15 seconds.

Checks every response's `X-RateLimit-Limit` header against the configured rpm as a self-check
that the right tenant was used. Thresholds fail the run if either count is zero: `count>0` on
both 200s and 429s, proving the limit engages *and* isn't blocking everything (which would mean
it's misconfigured, not "working").

Reports 200 vs. 429 counts and percentage, plus p50/p95 for the requests that got through.

## Output

Every scenario prints its summary to stdout and drops a copy under `load/results/<scenario>.md`
(gitignored - these are run outputs, not something to commit).

## Overriding tenant keys / URLs

```
make overhead BASE_URL=http://staging:8080 TENANT_KEY=gw_some_other_key
make cache REPEAT_PCT=50 CACHE_POOL_SIZE=10
make ratelimit RATELIMIT_KEY=gw_some_key RATELIMIT_RPM=15
```
