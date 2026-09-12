// Central place for the environment knobs every scenario needs. All overridable via `-e NAME=value`
// on the k6 command line; defaults point at the tenants seeded in docker/init.sql so scenarios
// work out of the box against a freshly-started local stack.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// loadtest-tenant, 1,000,000 rpm - used by scenarios 1-3. NOT demo-tenant (60 rpm): confirmed
// live that a 200-VU run against a 60 rpm tenant returns 429 for ~all of it, measuring
// RateLimitFilter instead of whatever the scenario is actually named for.
export const TENANT_API_KEY = __ENV.TENANT_API_KEY || 'gw_loadtest_key';

// loadtest-ratelimit-tenant, 20 rpm - used only by scenario 4, kept separate so tripping its
// limit doesn't drain demo-tenant's bucket for whatever scenario runs next.
export const RATELIMIT_TENANT_API_KEY = __ENV.RATELIMIT_TENANT_API_KEY || 'gw_loadtest_ratelimit_key';
export const RATELIMIT_TENANT_RPM = Number(__ENV.RATELIMIT_TENANT_RPM || 20);

// k6's default summaryTrendStats is ['avg','min','med','max','p(90)','p(95)'] - no p(50) or
// p(99) keys, which report.js's coreStats() reads by that exact name. Every scenario's options
// must set this explicitly or those columns silently read as 0.
export const SUMMARY_TREND_STATS = ['avg', 'min', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'];
