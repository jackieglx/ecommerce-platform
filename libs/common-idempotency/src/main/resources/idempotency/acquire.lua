local key = KEYS[1]
local processingTtl = tonumber(ARGV[1])
local token = ARGV[2]

local val = redis.call("GET", key)
if not val then
  redis.call("SET", key, "PROCESSING:" .. token, "NX", "EX", processingTtl)
  return "ACQUIRED"
end

if val == "DONE" then
  return "DONE"
end

return "PROCESSING"

