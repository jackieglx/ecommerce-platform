local key = KEYS[1]
local processingTtl = tonumber(ARGV[1])
local token = ARGV[2]

-- Single atomic attempt to acquire
local setRes = redis.call("SET", key, "PROCESSING:" .. token, "NX", "EX", processingTtl)
if setRes then
  return "ACQUIRED"
end

-- Someone already holds the key; inspect current value
local val = redis.call("GET", key)
if not val then
  -- Key vanished (rare due to atomic script); treat as processing to retry upstream
  return "PROCESSING"
end

if val == "DONE" then
  return "DONE"
end

if string.sub(val, 1, 5) == "DONE:" then
  return val
end

return "PROCESSING"

