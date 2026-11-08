-- Atomically claim the first available teller who speaks the call's language.
-- KEYS: call hash, queue zset, tellers set | ARGV: key prefix, language, callId
-- Teller keys are derived from ARGV[1]; all keys share the {stm} hash tag so this is Cluster-safe.
if redis.call('HGET', KEYS[1], 'state') ~= 'QUEUED' then return false end
local lang = ',' .. ARGV[2] .. ','
for _, id in ipairs(redis.call('SMEMBERS', KEYS[3])) do
  local tk = ARGV[1] .. 'teller:' .. id
  local av = redis.call('HGET', tk, 'available')
  if av == false then
    redis.call('SREM', KEYS[3], id)            -- presence expired: teller is gone
  elseif av == '1' and string.find(redis.call('HGET', tk, 'langs') or '', lang, 1, true) then
    redis.call('HSET', tk, 'available', '0')
    redis.call('HSET', KEYS[1], 'state', 'RINGING', 'tellerId', id)
    redis.call('ZREM', KEYS[2], ARGV[3])
    return id
  end
end
return false
