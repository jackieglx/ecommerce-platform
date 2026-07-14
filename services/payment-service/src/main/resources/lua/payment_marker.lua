-- Atomic payment marker write: HSET + PEXPIRE in one script
-- KEYS[1] = marker key
-- ARGV[1] = orderId
-- ARGV[2] = paymentId
-- ARGV[3] = paidAt (string, may be empty)
-- ARGV[4] = amountCents (string)
-- ARGV[5] = currency (string, may be empty)
-- ARGV[6] = ttlMillis (string number)

redis.call('HSET', KEYS[1],
  'orderId', ARGV[1],
  'paymentId', ARGV[2],
  'paidAt', ARGV[3],
  'amountCents', ARGV[4],
  'currency', ARGV[5]
)

local ttl = tonumber(ARGV[6])
if ttl ~= nil and ttl > 0 then
  redis.call('PEXPIRE', KEYS[1], ttl)
end

return 1

