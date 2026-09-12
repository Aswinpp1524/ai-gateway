import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

import { SUMMARY_TREND_STATS, TENANT_API_KEY } from '../lib/config.js';
import { postChatCompletion } from '../lib/http.js';
import { markdownSummary } from '../lib/report.js';

// Scenario 3 - cache effectiveness.
//
// setup() warms a fixed pool of canonical prompts against Ollama, one request each, so every
// one of them is guaranteed cached (see dev.gateway.core.cache.SemanticCache) before the timed
// run starts. During the run, each iteration either replays a warmed prompt (expected HIT) or
// mints a brand-new one (expected MISS), at the ratio given by REPEAT_PCT. The gateway echoes
// the actual outcome back on the X-Cache response header, so hit/miss latency is bucketed off
// that header directly rather than assumed from which branch the script took.
//
// No temperature override here (unlike scenarios 1 and 2) - a cacheable request needs
// temperature unset or <= gateway.cache.max-cacheable-temperature, and these prompts are meant
// to be cached.
//
// VUs and the miss rate both default conservatively for the same reason documented in
// scenario 2: a MISS here is a real Ollama call, and this machine's Ollama serializes inference
// badly enough that just 3 concurrent calls reliably tripped the gateway's circuit breaker.
// Every VU here can independently roll a miss at any iteration, so total concurrent Ollama load
// is probabilistic, not fixed - CACHE_VUS defaults low and REPEAT_PCT defaults high (fewer
// misses) to keep expected concurrent misses well under 1. Raise either if your Ollama can take
// it; watch for the same "circuit open" / latency-cliff signs called out in scenario 2.

const REPEAT_PCT = Number(__ENV.REPEAT_PCT || 85);
const POOL_SIZE = Number(__ENV.CACHE_POOL_SIZE || 20);
const VUS = Number(__ENV.CACHE_VUS || 5);

// Confirmed live that "unique miss prompt ${vu}-${iter}-${timestamp}-${random}" isn't unique
// enough for this purpose: nomic-embed-text embeds a fixed template with only trailing numbers
// changing as near-identical text, so at gateway.cache.similarity-threshold (0.95) almost every
// "miss" attempt landed as an unintended HIT (observed ratio 99.9% against an 85% target). Miss
// prompts need to differ in actual subject matter, not just in a numeric suffix, to reliably
// embed far enough apart. A random pair from a wide, unrelated word list gives a large
// combinatorial space of genuinely distinct topics instead.
const TOPICS = [
    'volcano', 'sonnet', 'gyroscope', 'compost', 'tectonic plate', 'opera', 'blockchain',
    'coral reef', 'harpsichord', 'aqueduct', 'permafrost', 'origami', 'lighthouse', 'sourdough',
    'telescope', 'mangrove', 'calligraphy', 'glacier', 'beehive', 'submarine', 'orchard',
    'windmill', 'meteor shower', 'catacomb', 'greenhouse', 'waterfall', 'observatory', 'quarry',
    'lantern', 'shipwreck', 'terrarium', 'aurora', 'labyrinth', 'watermill', 'geyser',
    'silk road', 'igloo', 'vineyard', 'planetarium', 'monsoon',
];

function randomTopic() {
    return TOPICS[Math.floor(Math.random() * TOPICS.length)];
}

const cacheHitDuration = new Trend('cache_hit_duration', true);
const cacheMissDuration = new Trend('cache_miss_duration', true);
const cacheHits = new Counter('cache_hits');
const cacheTotal = new Counter('cache_total');

export const options = {
    scenarios: {
        cache_effectiveness: {
            executor: 'constant-vus',
            vus: VUS,
            duration: '3m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
    summaryTrendStats: SUMMARY_TREND_STATS,
};

function poolPrompt(i) {
    return `In one sentence, explain what a ${TOPICS[i % TOPICS.length]} is.`;
}

export function setup() {
    const prompts = [];
    for (let i = 0; i < POOL_SIZE; i++) {
        const content = poolPrompt(i);
        const res = postChatCompletion(
            { model: 'llama3.2', messages: [{ role: 'user', content }] },
            TENANT_API_KEY,
        );
        if (res.status !== 200) {
            fail(`cache warm-up request ${i} failed with status ${res.status}: ${res.body}`);
        }
        prompts.push(content);
    }
    return { prompts };
}

export default function (data) {
    const isRepeat = Math.random() * 100 < REPEAT_PCT;
    const content = isRepeat
        ? data.prompts[Math.floor(Math.random() * data.prompts.length)]
        : `In one sentence, explain any connection between a ${randomTopic()} and a ${randomTopic()}.`;

    const res = postChatCompletion({ model: 'llama3.2', messages: [{ role: 'user', content }] }, TENANT_API_KEY);
    check(res, { 'status is 200': (r) => r.status === 200 });

    const cacheStatus = res.headers['X-Cache'];
    cacheTotal.add(1);
    if (cacheStatus === 'HIT') {
        cacheHits.add(1);
        cacheHitDuration.add(res.timings.duration);
    } else if (cacheStatus === 'MISS') {
        cacheMissDuration.add(res.timings.duration);
    }
}

export function handleSummary(data) {
    const hits = (data.metrics.cache_hits || {}).values.count || 0;
    const total = (data.metrics.cache_total || {}).values.count || 0;
    const hitRatioPct = total > 0 ? ((hits / total) * 100).toFixed(1) : '0.0';
    const hitDur = (data.metrics.cache_hit_duration || {}).values || {};
    const missDur = (data.metrics.cache_miss_duration || {}).values || {};

    const md = markdownSummary('Scenario 3: Cache effectiveness', data, [
        ['target repeat rate (%)', REPEAT_PCT],
        ['observed cache hit ratio (%)', hitRatioPct],
        ['cache hit p50 / p95 (ms)', `${(hitDur['p(50)'] || 0).toFixed(1)} / ${(hitDur['p(95)'] || 0).toFixed(1)}`],
        ['cache miss p50 / p95 (ms)', `${(missDur['p(50)'] || 0).toFixed(1)} / ${(missDur['p(95)'] || 0).toFixed(1)}`],
    ]);
    return {
        stdout: md + '\n',
        'results/03-cache-effectiveness.md': md,
    };
}
