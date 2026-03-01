local processingKey = KEYS[1]
local attemptKey = KEYS[2]
local job = ARGV[1]

redis.call('ZREM', processingKey, job)
redis.call('HDEL', attemptKey, job)
return 1

