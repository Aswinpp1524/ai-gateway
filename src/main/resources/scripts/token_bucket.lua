-- Atomic token-bucket rate limiter, continuous lazy refill.
--
-- KEYS[1] = bucket key, e.g. "ratelimit:{tenantId}"
-- ARGV[1] = capacity (tokens = tenant's rate_limit_rpm)
-- ARGV[2] = now, in epoch milliseconds - passed in from the caller rather than reading Redis's
--           TIME command, so the script's behaviour doesn't depend on a server clock read
--           happening inside it (keeps it trivially replication-safe and easy to test)
-- ARGV[3] = ttl_seconds - bucket key expiry. An idle tenant's bucket would conceptually be full
--           again anyway, so letting Redis reclaim the key is safe, not just a memory nicety.
--
-- Returns {allowed, remaining, retry_after_ms, reset_after_ms}:
--   allowed         1 if this request is permitted, 0 otherwise
--   remaining       tokens left AFTER this request, floored to an integer for the response header
--   retry_after_ms  ms until at least ONE token is available (0 when allowed)
--   reset_after_ms  ms until the bucket is back to FULL capacity - a different number from
--                   retry_after_ms, and reported on every call (not just denials) so a client
--                   with tokens left can still see how far it is from a completely fresh bucket

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local now = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local refill_per_ms = capacity / 60000.0

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
end

local elapsed = math.max(0, now - last_refill)
local refilled = math.min(capacity, tokens + elapsed * refill_per_ms)

local allowed = 0
local retry_after_ms = 0

if refilled >= 1 then
    allowed = 1
    refilled = refilled - 1
else
    retry_after_ms = math.ceil((1 - refilled) / refill_per_ms)
end

local reset_after_ms = math.ceil((capacity - refilled) / refill_per_ms)

redis.call('HMSET', key, 'tokens', tostring(refilled), 'last_refill', tostring(now))
redis.call('EXPIRE', key, ttl)

return {allowed, math.floor(refilled), retry_after_ms, reset_after_ms}
