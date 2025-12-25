local key = KEYS[1]
local token = ARGV[1]
local doneTtl = tonumber(ARGV[2])

local expected = "PROCESSING:" .. token
local current = redis.call("GET", key)
if current == expected then
  redis.call("SET", key, "DONE", "EX", doneTtl)
  return 1
end

return 0

