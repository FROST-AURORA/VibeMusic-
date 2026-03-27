-- KEYS[1]: lockKey
-- ARGV[1]: expected lock value

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end

return 0
