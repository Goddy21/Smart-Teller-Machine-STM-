-- Compare-and-set the call state. KEYS: call hash | ARGV: from, to, teller op (SET|CLEAR|NONE), tellerId
if redis.call('HGET', KEYS[1], 'state') ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'state', ARGV[2])
if ARGV[3] == 'SET' then redis.call('HSET', KEYS[1], 'tellerId', ARGV[4])
elseif ARGV[3] == 'CLEAR' then redis.call('HDEL', KEYS[1], 'tellerId') end
return 1
