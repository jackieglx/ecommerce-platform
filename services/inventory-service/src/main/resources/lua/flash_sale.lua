-- KEYS[1] = stock:{skuId}
-- KEYS[2] = buyers:{skuId}
-- KEYS[3] = order:{orderId} (optional idempotency)
-- KEYS[4] = outbox stream (same hash tag)
-- KEYS[5] = order snapshot (same hash tag)
-- KEYS[6] = price:{skuId} (preheated hash; same hash tag)
-- ARGV[1] = userId
-- ARGV[2] = qty
-- ARGV[3] = ttlSeconds (for order key)
-- ARGV[4] = snapshotTtlSeconds
-- ARGV[5] = buyersTtlSeconds
-- ARGV[6] = eventId
-- ARGV[7] = orderId
-- ARGV[8] = skuId
-- ARGV[9] = occurredAt
-- ARGV[10] = expireAt

local stockKey = KEYS[1]
local buyersKey = KEYS[2]
local orderKey = KEYS[3]
local streamKey = KEYS[4]
local snapshotKey = KEYS[5]
local priceKey = KEYS[6]
local userId = ARGV[1]
local qty = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local snapshotTtl = tonumber(ARGV[4])
local buyersTtl = tonumber(ARGV[5])
local eventId = ARGV[6]
local orderId = ARGV[7]
local skuId = ARGV[8]
local occurredAt = ARGV[9]
local expireAt = ARGV[10]

-- sold-out fast path: if no stock at all, reject immediately (single GET),
-- skipping EXISTS / SISMEMBER / HGET price and all downstream logic.
local stock0 = tonumber(redis.call('GET', stockKey) or "-1")
if stock0 <= 0 then
  return 0
end

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

-- price must be preheated; missing price is a config error -> fast fail (-3)
local priceCents = redis.call('HGET', priceKey, 'priceCents')
local currency = redis.call('HGET', priceKey, 'currency')
if (not priceCents) or (not currency) or priceCents == '' or currency == '' then
  return -3
end

redis.call('DECRBY', stockKey, qty)
redis.call('SADD', buyersKey, userId)
if buyersTtl and buyersTtl > 0 then
  redis.call('EXPIRE', buyersKey, buyersTtl)
end
if orderKey and orderKey ~= '' and ttl and ttl > 0 then
  redis.call('SET', orderKey, userId, 'EX', ttl)
end

-- order snapshot (derivable from orderId alone)
if snapshotKey and snapshotKey ~= '' and snapshotTtl and snapshotTtl > 0 then
  redis.call('HSET', snapshotKey,
    'orderId', orderId,
    'userId', userId,
    'skuId', skuId,
    'qty', qty,
    'priceCents', priceCents,
    'currency', currency,
    'occurredAt', occurredAt,
    'expireAt', expireAt
  )
  redis.call('EXPIRE', snapshotKey, snapshotTtl)
end
if streamKey and streamKey ~= '' then
  redis.call('XADD', streamKey, '*',
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
