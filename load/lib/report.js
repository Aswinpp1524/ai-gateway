// Hand-rolled markdown summary instead of importing k6's jslib.io textSummary helper, so these
// scripts run offline and don't pick up a formatting change from a URL nobody here controls.

export function coreStats(data) {
    const duration = (data.metrics.http_req_duration || {}).values || {};
    const reqs = (data.metrics.http_reqs || {}).values || {};
    const failed = (data.metrics.http_req_failed || {}).values || {};
    return {
        count: reqs.count || 0,
        rps: reqs.rate || 0,
        p50: duration['p(50)'] || 0,
        p95: duration['p(95)'] || 0,
        p99: duration['p(99)'] || 0,
        errorRatePct: (failed.rate || 0) * 100,
    };
}

function fmt(n, digits) {
    return Number(n).toFixed(digits === undefined ? 1 : digits);
}

/** extraRows: array of [label, value] pairs appended below the core stats table. */
export function markdownSummary(title, data, extraRows) {
    const s = coreStats(data);
    const lines = [
        `## ${title}`,
        '',
        '| metric | value |',
        '|---|---|',
        `| requests | ${s.count} |`,
        `| throughput (req/s) | ${fmt(s.rps, 2)} |`,
        `| p50 latency (ms) | ${fmt(s.p50)} |`,
        `| p95 latency (ms) | ${fmt(s.p95)} |`,
        `| p99 latency (ms) | ${fmt(s.p99)} |`,
        `| error rate (%) | ${fmt(s.errorRatePct, 2)} |`,
    ];
    (extraRows || []).forEach(([label, value]) => lines.push(`| ${label} | ${value} |`));
    lines.push('');
    return lines.join('\n');
}

/** Standard handleSummary: print to stdout (pasteable into a README) and also drop a copy under
 * results/ (relative to wherever k6 was invoked from - the Makefile always runs it from
 * load/, and creates that directory first) so a run's output survives after the terminal
 * scrolls away. */
export function handleSummaryDefault(title, filename, data, extraRows) {
    const md = markdownSummary(title, data, extraRows);
    return {
        stdout: md + '\n',
        [`results/${filename}`]: md,
    };
}
