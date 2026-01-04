local ready = KEYS[1]
local processing = KEYS[2]
local owners = KEYS[3]

local now = tonumber(ARGV[1])
local staleMs = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

local cutoff = now - staleMs
local ids = redis.call('ZRANGEBYSCORE', processing, '-inf', cutoff, 'LIMIT', 0, limit)
local reclaimed = {}
for _, id in ipairs(ids) do
  redis.call('ZREM', processing, id)
  redis.call('HDEL', owners, id)
  redis.call('ZADD', ready, now, id)
  table.insert(reclaimed, id)
end

return reclaimed

