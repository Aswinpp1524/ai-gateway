package dev.gateway.tenant;

/**
 * retryAfterMillis and resetAfterMillis are deliberately different numbers: retryAfterMillis is
 * how long until at least ONE token is available (only meaningful when denied), while
 * resetAfterMillis is how long until the bucket is back to FULL capacity (reported on every
 * call, matching GitHub's X-RateLimit-Reset convention) - a client with tokens left can still be
 * well short of a fully-reset bucket.
 */
record RateLimitResult(boolean allowed, int remaining, long retryAfterMillis, long resetAfterMillis) {}
