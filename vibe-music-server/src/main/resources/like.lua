-- KEYS[1]: likeKey, KEYS[2]: countKey
-- ARGV[1]: userId
-- ARGV[2]: baseExpireTime (基础过期时间，单位：秒，如 86400=24 小时)
-- ARGV[3]: randomOffset (随机偏移量，单位：秒，如 0-3600)
-- ARGV[4]: dbLikeCount (数据库中的点赞数，用于初始化缓存)

if redis.call('sismember', KEYS[1], ARGV[1]) == 1 then
    return 0 -- 已点赞，返回 0
else
    redis.call('sadd', KEYS[1], ARGV[1])
    
    -- ⭐ 关键修复：检查计数 Key 是否存在，不存在则用数据库的值初始化
    if redis.call('exists', KEYS[2]) == 0 then
        redis.call('set', KEYS[2], ARGV[4])
    end
    
    -- 计算实际过期时间 = 基础时间 + 随机偏移
    local expireTime = tonumber(ARGV[2]) + tonumber(ARGV[3])
    redis.call('expire', KEYS[1], expireTime)

    redis.call('incr', KEYS[2])
    redis.call('expire', KEYS[2], expireTime)
    
    return 1 -- 点赞成功
end
