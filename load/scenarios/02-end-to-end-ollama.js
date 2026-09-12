import { check } from 'k6';

import { SUMMARY_TREND_STATS, TENANT_API_KEY } from '../lib/config.js';
import { postChatCompletion } from '../lib/http.js';
import { handleSummaryDefault } from '../lib/report.js';

// Scenario 2 - realistic end-to-end, against Ollama running natively on the host.
//
// "Moderate load" here means genuinely low concurrency, and how low is hardware-dependent -
// confirmed live on this machine that Ollama serializes llama3.2 inference almost completely:
// 5 concurrent requests fired directly at it ranged from 6.8s to 88s+, not the ~7s each would
// take alone. At VUS=10 (this scenario's first draft), that queueing routinely pushed calls
// past gateway.resilience.timeout.seconds (30s), which triggered retries that piled on MORE
// competing load without ever cancelling the original request server-side, which tripped the
// ollama circuit breaker open - after which nearly every request got an instant 503 "circuit
// open" rather than a real response. The result looked like a load test (341 req/s, p50 4ms)
// but was actually measuring resilience/circuit-breaker behavior under overload, not end-to-end
// latency, which is what this scenario is named for.
//
// VUS defaults to 1 (fully sequential - the load test itself never issues concurrent Ollama
// calls) because even 3 wasn't safe on this machine: it also fell into the same open-circuit
// state within the first couple of minutes. If you have GPU-backed Ollama or a beefier box, you
// can raise VUS - but watch for "circuit open" in response bodies or a sudden drop in p50/p95
// alongside a spike in error rate. Either is the signal that VUS is past your Ollama's real
// concurrent capacity, not a gateway problem: back it off and re-run.
//
// Same temperature=0.7 trick as scenario 1 to bypass the semantic cache, so this measures a
// genuine router -> provider round trip every time, on the same footing as scenario 1's numbers.
//
// This script does not compute "gateway overhead" itself - it has no way to see the split
// between our code and Ollama's inference time from the client side. Run scenario 1 first, note
// its p50/p95, then diff this scenario's p50/p95 against it: the remainder is an estimate of
// time spent actually waiting on the model. Put both scenarios' numbers side by side in the
// README rather than trying to compute the delta here.

const VUS = Number(__ENV.E2E_VUS || 1);

export const options = {
    scenarios: {
        end_to_end_ollama: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '15s', target: VUS },
                { duration: '90s', target: VUS },
                { duration: '15s', target: 0 },
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
        model: 'llama3.2',
        temperature: 0.7,
        messages: [{ role: 'user', content: `load test iteration ${__ITER}-${__VU}` }],
    };
    const res = postChatCompletion(payload, TENANT_API_KEY);
    check(res, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return handleSummaryDefault('Scenario 2: End-to-end (Ollama)', '02-end-to-end-ollama.md', data, [
        ['target VUs', VUS],
    ]);
}
