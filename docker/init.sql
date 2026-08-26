CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenants (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         name TEXT NOT NULL,
                         monthly_budget_micros BIGINT NOT NULL DEFAULT 5000000,
                         rate_limit_rpm INT NOT NULL DEFAULT 60,
                         created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                          key_hash TEXT NOT NULL UNIQUE,
                          active BOOLEAN NOT NULL DEFAULT true,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cache_entries (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                               model TEXT NOT NULL,
                               prompt_text TEXT NOT NULL,
                               embedding vector(768) NOT NULL,
                               response_json JSONB NOT NULL,
                               hit_count INT NOT NULL DEFAULT 0,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_cache_tenant_model ON cache_entries (tenant_id, model);
CREATE INDEX idx_cache_expires ON cache_entries (expires_at);

CREATE TABLE usage_log (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                           requested_model TEXT NOT NULL,
                           served_by TEXT NOT NULL,
                           prompt_tokens INT NOT NULL DEFAULT 0,
                           completion_tokens INT NOT NULL DEFAULT 0,
                           cost_micros BIGINT NOT NULL DEFAULT 0,
                           cache_hit BOOLEAN NOT NULL DEFAULT false,
                           latency_ms INT NOT NULL,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_tenant_time ON usage_log (tenant_id, created_at DESC);

INSERT INTO tenants (id, name, rate_limit_rpm)
VALUES ('11111111-1111-1111-1111-111111111111', 'demo-tenant', 60);

INSERT INTO api_keys (tenant_id, key_hash)
VALUES ('11111111-1111-1111-1111-111111111111',
        encode(sha256('gw_demo_key_12345'::bytea), 'hex'));