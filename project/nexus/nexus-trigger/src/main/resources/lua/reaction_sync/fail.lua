local processingKey = KEYS[1]
local delayKey = KEYS[2]
local attemptKey = KEYS[3]
local dlqKey = KEYS[4]

local job = ARGV[1]
local dueMs = tonumber(ARGV[2])
local maxAttempt = tonumber(ARGV[3])
local reason = ARGV[4]

local attempt = tonumber(redis.call('HGET', attemptKey, job) or '0')
redis.call('ZREM', processingKey, job)

if attempt >= maxAttempt then
  redis.call('HDEL', attemptKey, job)
  redis.call('LPUSH', dlqKey, job .. '|' .. tostring(attempt) .. '|' .. (reason or ''))
  return {0, attempt}
end

local existing = redis.call('ZSCORE', delayKey, job)
if (not existing) or (tonumber(existing) > dueMs) then
  redis.call('ZADD', delayKey, dueMs, job)
end
return {1, attempt}

