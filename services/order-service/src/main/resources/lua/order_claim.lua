local ready = KEYS[1]
local processing = KEYS[2]
local owners = KEYS[3]

local now = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local token = ARGV[3]

local ids = redis.call('ZRANGEBYSCORE', ready, '-inf', now, 'LIMIT', 0, limit)
local claimed = {}
for _, id in ipairs(ids) do
  redis.call('ZREM', ready, id)
  redis.call('ZADD', processing, now, id)
  redis.call('HSET', owners, id, token)
  table.insert(claimed, id)
end

return claimed

