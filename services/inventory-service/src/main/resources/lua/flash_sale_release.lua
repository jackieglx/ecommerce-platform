-- KEYS[1] = stock:{skuId}
-- KEYS[2] = buyers:{skuId}
-- KEYS[3] = order:{orderId}
-- ARGV[1] = qty

local stockKey = KEYS[1]
local buyersKey = KEYS[2]
local orderKey = KEYS[3]
local qty = tonumber(ARGV[1])

local userId = redis.call('GET', orderKey)
if not userId or userId == '' then
  return 0
end

local removed = redis.call('SREM', buyersKey, userId)
if removed == 1 then
  redis.call('INCRBY', stockKey, qty)
end

redis.call('DEL', orderKey)

return removed


