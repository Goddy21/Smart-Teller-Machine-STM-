-- Pop due deadlines. Only the node whose ZREM succeeds handles an item. KEYS: deadlines zset | ARGV: now (ms)
local out = {}
for _, m in ipairs(redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, 50)) do
  if redis.call('ZREM', KEYS[1], m) == 1 then out[#out + 1] = m end
end
return out
