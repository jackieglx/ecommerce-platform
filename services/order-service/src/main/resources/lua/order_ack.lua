local processing = KEYS[1]
local owners = KEYS[2]

local orderId = ARGV[1]
local token = ARGV[2]

local current = redis.call('HGET', owners, orderId)
if current ~= token then
  return 0
end

redis.call('ZREM', processing, orderId)
redis.call('HDEL', owners, orderId)
return 1

