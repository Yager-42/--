local delayKey = KEYS[1]
local processingKey = KEYS[2]
local attemptKey = KEYS[3]

local nowMs = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local leaseMs = tonumber(ARGV[3])

-- 先把 processing 里“超时没 ack”的任务放回 delay（防止 worker 挂了任务永远卡住）
local expired = redis.call('ZRANGEBYSCORE', processingKey, '-inf', nowMs, 'LIMIT', 0, limit)
for i, job in ipairs(expired) do
  redis.call('ZREM', processingKey, job)
  local existing = redis.call('ZSCORE', delayKey, job)
  if (not existing) or (tonumber(existing) > nowMs) then
    redis.call('ZADD', delayKey, nowMs, job)
  end
end

local jobs = redis.call('ZRANGEBYSCORE', delayKey, '-inf', nowMs, 'LIMIT', 0, limit)
local out = {}
for i, job in ipairs(jobs) do
  -- 关键点：enqueue 允许“processing 期间再次入队”，所以 delay 里可能存在 processing 中的 job；必须跳过避免并发执行
  if (not redis.call('ZSCORE', processingKey, job)) then
    redis.call('ZREM', delayKey, job)
    redis.call('ZADD', processingKey, nowMs + leaseMs, job)
    local attempt = redis.call('HINCRBY', attemptKey, job, 1)
    table.insert(out, job)
    table.insert(out, tostring(attempt))
  end
end
return out

