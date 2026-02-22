-- Request Rate Limiter (Token Bucket)
--
-- KEYS[1] : The unique rate limit key (e.g., "rate_limit:shop:user_123")
--
-- ARGV[1] : Capacity (Maximum tokens allowed in the bucket)
-- ARGV[2] : Refill Rate (Tokens added per second)
-- ARGV[3] : Requested Tokens (usually 1)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Redis server time (seconds)
local redis_time = redis.call("TIME")
local now = tonumber(redis_time[1]) 

-- Fetch current state
local state = redis.call("HMGET", key, "tokens", "last_refilled")
local tokens_left = tonumber(state[1])
local last_refilled = tonumber(state[2])

-- Initialize if key doesn't exist
if not tokens_left then
  tokens_left = capacity
  last_refilled = now
end

-- Refill Logic (seconds-based)
local delta = math.max(0, now - last_refilled)
local tokens_to_add = delta * refill_rate

if tokens_to_add > 0 then
  tokens_left = math.min(capacity, tokens_left + tokens_to_add)
  last_refilled = now
end

-- Check consumption
local allowed = 0
if tokens_left >= requested then
  tokens_left = tokens_left - requested
  allowed = 1
end

-- Save state
redis.call("HSET", key, "tokens", tokens_left, "last_refilled", last_refilled)

-- TTL Safety Guard (seconds)
local ttl
if refill_rate > 0 then
   ttl = math.ceil(capacity / refill_rate * 2)
else
   ttl = 60 
end
redis.call("EXPIRE", key, ttl)

return { allowed, math.floor(tokens_left) }