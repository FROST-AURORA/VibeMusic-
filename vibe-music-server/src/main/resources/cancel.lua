-- KEYS[1]: likeKey, KEYS[2]: countKey
-- ARGV[1]: userId
-- ARGV[2]: baseExpireTime (基础过期时间，单位：秒)
-- ARGV[3]: randomOffset (随机偏移量，单位：秒)
-- ARGV[4]: dbLikeCount (数据库中的点赞数，用于初始化缓存)

if redis.call('sismember', KEYS[1], ARGV[1]) == 0 then
    return 0 -- 未点赞，返回 0
else
    redis.call('srem', KEYS[1], ARGV[1])
    
    -- ⭐ 关键修复：检查计数 Key 是否存在，不存在则用数据库的值初始化
    if redis.call('exists', KEYS[2]) == 0 then
        local dbCount = tonumber(ARGV[4])
        if dbCount > 0 then
            redis.call('set', KEYS[2], dbCount)
        else
            redis.call('set', KEYS[2], 0)
        end
    end
    
    local count = redis.call('decr', KEYS[2])
    if count < 0 then
        redis.call('set', KEYS[2], 0)
    end
    
    -- 更新过期时间（防止取消点赞后缓存立即过期）
    local expireTime = tonumber(ARGV[2]) + tonumber(ARGV[3])
    redis.call('expire', KEYS[1], expireTime)
    redis.call('expire', KEYS[2], expireTime)
    
    return 1 -- 取消成功
end