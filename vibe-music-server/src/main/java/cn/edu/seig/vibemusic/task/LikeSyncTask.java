package cn.edu.seig.vibemusic.task;


import cn.edu.seig.vibemusic.model.entity.Comment;
import cn.edu.seig.vibemusic.service.ICommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.COMMENT_LIKE_COUNT;

@Slf4j
@Component
public class LikeSyncTask {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ICommentService commentService;

    // 每 5 分钟执行一次
    @Scheduled(cron = "0 0/5 * * * ?")
    public void syncLikeCountToDb() {
        log.info("开始同步 Redis 点赞数据至数据库...");

        // 1. 使用 SCAN 安全遍历 Key
        ScanOptions options = ScanOptions.scanOptions() //创建 ScanOptions 构建器
                .match(COMMENT_LIKE_COUNT + "*") //设置要匹配的 Key 的模式
                .count(100) // 每次读取 100 条
                .build();

        // 2. 这里的 execute 会自动处理 Cursor 的迭代和关闭
        List<Comment> updateList = new ArrayList<>();
        //请 Redis 帮我执行一段代码，这是连接对象(connection)，你随便用
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            // 创建一个 Cursor 对象，用来迭代所有符合条件的 Key
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next());//将下一个元素byte[] 转成 String
                    String countStr = stringRedisTemplate.opsForValue().get(key);
                    if (countStr != null) {
                        Long commentId = Long.valueOf(key.substring(COMMENT_LIKE_COUNT.length()));
                        Comment comment = new Comment();
                        comment.setCommentId(commentId);
                        comment.setLikeCount(Long.valueOf(countStr));
                        updateList.add(comment);
                    }
                    // 3. 分批执行数据库更新，防止 updateList 过大内存溢出
                    if (updateList.size() >= 500) {
                        //批量更新数据库
                        commentService.updateBatchById(updateList);
                        //清空列表
                        updateList.clear();
                    }
                }
            } catch (Exception e) {
                log.error("同步点赞数据异常", e);
            }
            //因为 RedisCallback<Void> 的泛型是 Void，必须返回 null
            return null;
        });

        // 4. 处理剩余数据
        if (!updateList.isEmpty()) {
            commentService.updateBatchById(updateList);
        }
        log.info("点赞数据同步完成。");
    }
}
