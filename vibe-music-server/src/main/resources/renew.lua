-- KEYS[1]: lockKey
-- ARGV[1]: expected lock value
-- ARGV[2]: new ttl seconds

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('expire', KEYS[1], ARGV[2])
end

return 0
