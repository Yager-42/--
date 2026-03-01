local delayKey = KEYS[1]
local job = ARGV[1]
local dueMs = tonumber(ARGV[2])

local existing = redis.call('ZSCORE', delayKey, job)
if (not existing) or (tonumber(existing) > dueMs) then
  redis.call('ZADD', delayKey, dueMs, job)
end
return 1

