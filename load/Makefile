# One command per scenario. See load/README.md for what each one needs running first.
#
# Override any of these on the command line, e.g.:
#   make -C load overhead BASE_URL=http://localhost:8080 TENANT_KEY=gw_loadtest_key

BASE_URL          ?= http://localhost:8080
TENANT_KEY        ?= gw_loadtest_key
RATELIMIT_KEY     ?= gw_loadtest_ratelimit_key
RATELIMIT_RPM     ?= 20
REPEAT_PCT        ?= 85
CACHE_POOL_SIZE   ?= 20
CACHE_VUS         ?= 5
E2E_VUS           ?= 1

K6_ENV = -e BASE_URL=$(BASE_URL) -e TENANT_API_KEY=$(TENANT_KEY)

.PHONY: overhead e2e cache ratelimit seed-tenants all results-dir

results-dir:
	@mkdir -p results

overhead: results-dir
	k6 run $(K6_ENV) scenarios/01-gateway-overhead.js

e2e: results-dir
	k6 run $(K6_ENV) -e E2E_VUS=$(E2E_VUS) scenarios/02-end-to-end-ollama.js

cache: results-dir
	k6 run $(K6_ENV) -e REPEAT_PCT=$(REPEAT_PCT) -e CACHE_POOL_SIZE=$(CACHE_POOL_SIZE) -e CACHE_VUS=$(CACHE_VUS) scenarios/03-cache-effectiveness.js

ratelimit: results-dir
	k6 run -e BASE_URL=$(BASE_URL) -e RATELIMIT_TENANT_API_KEY=$(RATELIMIT_KEY) -e RATELIMIT_TENANT_RPM=$(RATELIMIT_RPM) scenarios/04-rate-limiting.js

# Idempotent: safe to run against a Postgres volume that was already initialized before these
# tenants existed in docker/init.sql (docker-entrypoint-initdb.d scripts only run once, on a
# fresh volume - they don't retroactively apply to one that already has data in it).
seed-tenants:
	docker exec gw-postgres psql -U gateway -d gateway -c "INSERT INTO tenants (id, name, rate_limit_rpm) VALUES ('22222222-2222-2222-2222-222222222222', 'loadtest-ratelimit-tenant', 20) ON CONFLICT (id) DO NOTHING;"
	docker exec gw-postgres psql -U gateway -d gateway -c "INSERT INTO api_keys (tenant_id, key_hash) VALUES ('22222222-2222-2222-2222-222222222222', encode(sha256('gw_loadtest_ratelimit_key'::bytea), 'hex')) ON CONFLICT (key_hash) DO NOTHING;"
	docker exec gw-postgres psql -U gateway -d gateway -c "INSERT INTO tenants (id, name, rate_limit_rpm) VALUES ('33333333-3333-3333-3333-333333333333', 'loadtest-tenant', 1000000) ON CONFLICT (id) DO NOTHING;"
	docker exec gw-postgres psql -U gateway -d gateway -c "INSERT INTO api_keys (tenant_id, key_hash) VALUES ('33333333-3333-3333-3333-333333333333', encode(sha256('gw_loadtest_key'::bytea), 'hex')) ON CONFLICT (key_hash) DO NOTHING;"

# Run sequentially, never in parallel - ratelimit intentionally exhausts a tenant's bucket, and
# cache's setup() phase needs Ollama's undivided attention to warm its prompt pool reliably.
all: overhead e2e cache ratelimit
