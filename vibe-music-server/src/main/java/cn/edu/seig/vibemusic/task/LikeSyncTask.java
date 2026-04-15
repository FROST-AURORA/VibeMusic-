package cn.edu.seig.vibemusic.task;

import cn.edu.seig.vibemusic.model.entity.Comment;
import cn.edu.seig.vibemusic.service.ICommentService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.COMMENT_LIKE_COUNT;

@Slf4j
@Component
public class LikeSyncTask {
    private static final String LIKE_SYNC_LOCK_KEY = "lock:task:comment:like-sync";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ICommentService commentService;
    @Autowired
    private RedissonClient redissonClient;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void syncLikeCountToDb() {
        RLock lock = redissonClient.getLock(LIKE_SYNC_LOCK_KEY);
        boolean locked = false;

        try {
            locked = lock.tryLock(0, 4, TimeUnit.MINUTES);// 0代表定时任务不需要排队等。别的实例在跑，这台直接跳过本轮就行
            if (!locked) {
                log.info("点赞同步任务跳过：其他实例正在执行。");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();// 恢复中断标志
            log.warn("点赞同步任务获取分布式锁时被中断。", e);
            return;
        }

        log.info("开始同步 Redis 点赞数据至数据库...");

        try {
            //创建一个"扫描规则"
            ScanOptions options = ScanOptions.scanOptions()
                    .match(COMMENT_LIKE_COUNT + "*")
                    .count(100) //避免一次性取出太多数据导致 Redis 卡顿
                    .build();

            List<Comment> updateList = new ArrayList<>();
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                //// cursor 就像一个"扫描仪"，会一个一个地找出符合条件的 key
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next(), StandardCharsets.UTF_8);
                        String countStr = stringRedisTemplate.opsForValue().get(key);
                        if (countStr != null) {
                            Long commentId = Long.valueOf(key.substring(COMMENT_LIKE_COUNT.length()));
                            Comment comment = new Comment();
                            comment.setCommentId(commentId);
                            comment.setLikeCount(Long.valueOf(countStr));
                            updateList.add(comment);
                        }
                        //攒够 500 条再一次性更新
                        if (updateList.size() >= 500) {
                            commentService.updateBatchById(updateList);
                            updateList.clear();
                        }
                    }
                } catch (Exception e) {
                    log.error("同步点赞数据异常", e);
                }
                return null;
            });

            if (!updateList.isEmpty()) {
                commentService.updateBatchById(updateList);
            }

            log.info("点赞数据同步完成。");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
