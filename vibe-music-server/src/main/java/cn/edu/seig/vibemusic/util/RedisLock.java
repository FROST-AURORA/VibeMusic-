package cn.edu.seig.vibemusic.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.LOCK_TTL_SECONDS;

@Component
public class RedisLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "lock:";

    // Lua脚本（原子解锁）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试加锁
     */
    public String tryLock(String key, long timeoutSec) {
        String lockKey = LOCK_PREFIX + key;
        String value = UUID.randomUUID().toString();
        long effectiveTimeout = timeoutSec > 0 ? timeoutSec : LOCK_TTL_SECONDS;

        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, value, effectiveTimeout, TimeUnit.SECONDS);
        // 成功则返回value，失败则返回null
        return Boolean.TRUE.equals(success) ? value : null;
    }

    /**
     * 释放锁（带校验）
     */
    public void unlock(String key, String value) {
        String lockKey = LOCK_PREFIX + key;

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                value
        );
    }
}
