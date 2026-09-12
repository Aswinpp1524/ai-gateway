import { check } from 'k6';
import { Counter } from 'k6/metrics';

import { SUMMARY_TREND_STATS, TENANT_API_KEY } from '../lib/config.js';
import { postChatCompletion } from '../lib/http.js';
import { handleSummaryDefault } from '../lib/report.js';

// Scenario 1 - gateway overhead (the headline number).
//
// Targets the stub provider (see dev.gateway.provider.stub), which does no I/O and returns in
// ~0ms, so everything measured here is auth, rate limiting, budget enforcement, routing,
// resilience wrapping, and metering - the gateway's own code, not a vendor's network.
//
// Requires the gateway running with the loadtest profile active:
//   mvn spring-boot:run -Dspring-boot.run.profiles=loadtest
//
// temperature is set above gateway.cache.max-cacheable-temperature (0.3) so every request is
// genuinely routed to the provider - a cacheable request would skip routing after the first hit
// and this scenario would end up measuring the cache path instead of the one it's named for.

export const successfulRequests = new Counter('successful_requests');

export const options = {
    scenarios: {
        gateway_overhead: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '1m', target: 200 },
                { duration: '2m', target: 200 },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
    summaryTrendStats: SUMMARY_TREND_STATS,
};

export default function () {
    const payload = {
        model: 'stub-fast',
        temperature: 0.7,
        messages: [{ role: 'user', content: `load test iteration ${__ITER}-${__VU}` }],
    };
    const res = postChatCompletion(payload, TENANT_API_KEY);
    const ok = check(res, { 'status is 200': (r) => r.status === 200 });
    if (ok) {
        successfulRequests.add(1);
    }
}

export function handleSummary(data) {
    const successCount = (data.metrics.successful_requests || {}).values || {};
    return handleSummaryDefault('Scenario 1: Gateway overhead (stub provider)', '01-gateway-overhead.md', data, [
        ['successful (2xx) requests', successCount.count || 0],
    ]);
}
