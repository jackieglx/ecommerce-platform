local key = KEYS[1]
local token = ARGV[1]

local expected = "PROCESSING:" .. token
local current = redis.call("GET", key)
if current == expected then
  redis.call("DEL", key)
  return 1
end

return 0

