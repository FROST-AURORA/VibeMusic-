package cn.edu.seig.vibemusic.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.LOCK_TTL_SECONDS;

@Component
public class RedisLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "lock:";

    // Lua 脚本，确保只有持有锁的线程才能续期或释放
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private static final DefaultRedisScript<Long> RENEW_SCRIPT;
    private static final ScheduledExecutorService LOCK_RENEW_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new LockRenewThreadFactory());

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

        RENEW_SCRIPT = new DefaultRedisScript<>();
        RENEW_SCRIPT.setLocation(new ClassPathResource("renew.lua"));
        RENEW_SCRIPT.setResultType(Long.class);
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
        return Boolean.TRUE.equals(success) ? value : null;
    }

    /**
     * 释放锁，校验 value 防止误删
     */
    public void unlock(String key, String value) {
        String lockKey = LOCK_PREFIX + key;

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                value
        );
    }

    /**
     * 续期锁，只有当前持有者才能刷新 TTL
     */
    public boolean renew(String key, String value, long timeoutSec) {
        String lockKey = LOCK_PREFIX + key;
        long effectiveTimeout = timeoutSec > 0 ? timeoutSec : LOCK_TTL_SECONDS;

        Long result = stringRedisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(lockKey),
                value,
                String.valueOf(effectiveTimeout)
        );
        return Long.valueOf(1L).equals(result);//防止空指针异常，null
    }

    /**
     * 为当前锁启动一个轻量 watchdog，避免长查询期间锁提前过期
     * 参数：锁的 key、你的 UUID、过期时间
     * 返回值：ScheduledFuture（可以理解为"任务遥控器"，可以用来取消任务）
     */
    public ScheduledFuture<?> scheduleRenewal(String key, String value, long timeoutSec) {
        long effectiveTimeout = timeoutSec > 0 ? timeoutSec : LOCK_TTL_SECONDS;
        //核心逻辑：每过 effectiveTimeout / 3 的时间就自动续期一次
        long renewPeriod = Math.max(1L, effectiveTimeout / 3);
        //创建一个数组来保存任务对象
        final ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        //定义一个"每隔一段时间要自动执行的任务"！
        Runnable task = () -> {
            try {
                boolean renewed = renew(key, value, effectiveTimeout);
                //如果续期失败，就停止任务
                if (!renewed && holder[0] != null) {//确保任务对象存在（防止空指针）
                    holder[0].cancel(false);
                }
            } catch (Exception e) {
                //如果出错，也停止任务
                if (holder[0] != null) {
                    holder[0].cancel(false);
                }
            }
        };
        holder[0] = LOCK_RENEW_EXECUTOR.scheduleAtFixedRate(
                task, //要执行的任务（就是上面的续期逻辑）
                renewPeriod, //延迟多久后第一次执行
                renewPeriod, //之后每隔多久执行一次
                TimeUnit.SECONDS //单位是秒
        );
        return holder[0];
    }

    private static class LockRenewThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "redis-lock-renew");
            thread.setDaemon(true);
            return thread;
        }
    }
}
