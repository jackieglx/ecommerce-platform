-- KEYS[1] = stock:{skuId}
-- KEYS[2] = buyers:{skuId}
-- KEYS[3] = order:{orderId} (optional idempotency)
-- KEYS[4] = outbox stream (same hash tag)
-- ARGV[1] = userId
-- ARGV[2] = qty
-- ARGV[3] = ttlSeconds (for order key)
-- ARGV[4] = eventId
-- ARGV[5] = orderId
-- ARGV[6] = skuId
-- ARGV[7] = occurredAt
-- ARGV[8] = priceCents
-- ARGV[9] = currency
-- ARGV[10] = expireAt

local stockKey = KEYS[1]
local buyersKey = KEYS[2]
local orderKey = KEYS[3]
local streamKey = KEYS[4]
local userId = ARGV[1]
local qty = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local eventId = ARGV[4]
local orderId = ARGV[5]
local skuId = ARGV[6]
local occurredAt = ARGV[7]
local priceCents = ARGV[8]
local currency = ARGV[9]
local expireAt = ARGV[10]

-- idempotency: if orderKey exists, treat as duplicate
if orderKey and orderKey ~= '' then
  if redis.call('EXISTS', orderKey) == 1 then
    return -1
  end
end

-- one per buyer
if redis.call('SISMEMBER', buyersKey, userId) == 1 then
  return -1
end

local stock = tonumber(redis.call('GET', stockKey) or "-1")
if stock < qty then
  return 0
end

redis.call('DECRBY', stockKey, qty)
redis.call('SADD', buyersKey, userId)
if orderKey and orderKey ~= '' and ttl and ttl > 0 then
  redis.call('SET', orderKey, userId, 'EX', ttl)
end
if streamKey and streamKey ~= '' then
  redis.call('XADD', streamKey, 'MAXLEN', '~', 100000, '*',
    'eventId', eventId,
    'orderId', orderId,
    'userId', userId,
    'skuId', skuId,
    'qty', qty,
    'priceCents', priceCents,
    'currency', currency,
    'occurredAt', occurredAt,
    'expireAt', expireAt
  )
end

return 1

