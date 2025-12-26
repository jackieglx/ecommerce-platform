local key = KEYS[1]
local token = ARGV[1]
local doneTtl = tonumber(ARGV[2])
local resultPointer = ARGV[3]

local expected = "PROCESSING:" .. token
local current = redis.call("GET", key)
if current == expected then
  if resultPointer and string.len(resultPointer) > 0 then
    redis.call("SET", key, "DONE:" .. resultPointer, "EX", doneTtl)
  else
    redis.call("SET", key, "DONE", "EX", doneTtl)
  end
  return 1
end

return 0

