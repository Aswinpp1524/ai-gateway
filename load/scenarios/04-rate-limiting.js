import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

import { RATELIMIT_TENANT_API_KEY, RATELIMIT_TENANT_RPM, SUMMARY_TREND_STATS } from '../lib/config.js';
import { postChatCompletion } from '../lib/http.js';
import { markdownSummary } from '../lib/report.js';

// Scenario 4 - rate limiting.
//
// Uses loadtest-ratelimit-tenant (20 rpm, seeded separately from demo-tenant in docker/init.sql
// specifically for this scenario - see its comment there for why). 10 VUs firing with no sleep
// will burn through a 20-token bucket almost immediately and keep tripping it for the rest of
// the run, proving the limit holds under real concurrent load rather than a single curl loop.
//
// Targets the stub provider, and for a reason specific to this scenario (unlike scenarios 1-3,
// where it's about isolating overhead): confirmed live that pointing this at llama3.2 instead
// produced exactly ONE completed iteration in 45s. RateLimitFilter runs before routing, so a
// REJECTED request does return instantly either way - but an ALLOWED one still waits for the
// full response, and the token bucket starts full (20 tokens), so most of the first wave of
// requests across 10 VUs get allowed through to a ~27s real Ollama call, leaving the bucket
// never actually exercised within a short test window. This requires the loadtest profile
// active (see load/README.md), same as scenario 1.

const allowed = new Counter('rate_limit_allowed');
const limited = new Counter('rate_limit_429');
const allowedDuration = new Trend('rate_limit_allowed_duration', true);

export const options = {
    scenarios: {
        rate_limiting: {
            executor: 'constant-vus',
            vus: 10,
            duration: '15s',
        },
    },
    thresholds: {
        // Proves both halves of the contract: the limit actually engages, and it isn't
        // rejecting everything (which would mean it's misconfigured, not "working").
        rate_limit_429: ['count>0'],
        rate_limit_allowed: ['count>0'],
    },
    summaryTrendStats: SUMMARY_TREND_STATS,
};

export default function () {
    const payload = {
        model: 'stub-fast',
        temperature: 0.7,
        messages: [{ role: 'user', content: `rate limit probe ${__VU}-${__ITER}` }],
    };
    const res = postChatCompletion(payload, RATELIMIT_TENANT_API_KEY);

    check(res, {
        'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        'X-RateLimit-Limit matches configured rpm': (r) => r.headers['X-RateLimit-Limit'] === String(RATELIMIT_TENANT_RPM),
    });

    if (res.status === 200) {
        allowed.add(1);
        allowedDuration.add(res.timings.duration);
    } else if (res.status === 429) {
        limited.add(1);
    }
}

export function handleSummary(data) {
    const allowedCount = (data.metrics.rate_limit_allowed || {}).values.count || 0;
    const limitedCount = (data.metrics.rate_limit_429 || {}).values.count || 0;
    const total = allowedCount + limitedCount;
    const limitedPct = total > 0 ? ((limitedCount / total) * 100).toFixed(1) : '0.0';
    const allowedDur = (data.metrics.rate_limit_allowed_duration || {}).values || {};

    const md = markdownSummary('Scenario 4: Rate limiting', data, [
        ['configured limit (rpm)', RATELIMIT_TENANT_RPM],
        ['200 (allowed)', allowedCount],
        ['429 (rate limited)', limitedCount],
        ['rate limited (%)', limitedPct],
        ['allowed-request p50 / p95 (ms)', `${(allowedDur['p(50)'] || 0).toFixed(1)} / ${(allowedDur['p(95)'] || 0).toFixed(1)}`],
    ]);
    return {
        stdout: md + '\n',
        'results/04-rate-limiting.md': md,
    };
}
