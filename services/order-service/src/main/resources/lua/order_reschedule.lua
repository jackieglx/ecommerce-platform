-- Atomically reschedule an in-processing task back to ready with a new score.
-- KEYS[1] = processing zset
-- KEYS[2] = owners hash
-- KEYS[3] = ready zset
-- ARGV[1] = id (orderId)
-- ARGV[2] = token
-- ARGV[3] = nextScoreMillis

local processing = KEYS[1]
local owners = KEYS[2]
local ready = KEYS[3]

local id = ARGV[1]
local token = ARGV[2]
local nextScore = tonumber(ARGV[3])

local current = redis.call('HGET', owners, id)
if current ~= token then
  return 0
end

redis.call('ZREM', processing, id)
redis.call('HDEL', owners, id)
redis.call('ZADD', ready, nextScore, id)
return 1

