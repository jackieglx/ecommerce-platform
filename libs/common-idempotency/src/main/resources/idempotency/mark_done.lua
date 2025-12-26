local key = KEYS[1]
local token = ARGV[1]
local doneTtl = tonumber(ARGV[2])
local resultPointer = ARGV[3]
local payload = ARGV[4] or ""

local expected = "PROCESSING:" .. token
local current = redis.call("GET", key)
if current then
  if string.sub(current, 1, string.len(expected)) == expected then
    local storedPayload = string.sub(current, string.len(expected) + 2) -- skip the following ":"
    if payload == "" then
      payload = storedPayload
    end
    if storedPayload ~= payload then
      return 0
    end
    if resultPointer and string.len(resultPointer) > 0 then
      redis.call("SET", key, "DONE:" .. payload .. "\n" .. resultPointer, "EX", doneTtl)
    else
      redis.call("SET", key, "DONE:" .. payload .. "\n", "EX", doneTtl)
    end
    return 1
  end
end

return 0

