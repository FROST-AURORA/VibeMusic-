package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.CommentMapper;
import cn.edu.seig.vibemusic.model.dto.CommentPlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.CommentSongDTO;
import cn.edu.seig.vibemusic.model.entity.Comment;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ICommentService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements ICommentService {

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 基础过期时间：24 小时
    private static final long BASE_EXPIRE_TIME = COMMENT_LIKE_CACHE_TTL_SECONDS;
    // 最大随机偏移：1 小时
    private static final long MAX_RANDOM_OFFSET = COMMENT_LIKE_CACHE_RANDOM_OFFSET_SECONDS;


    private static final DefaultRedisScript<Long> LIKE_SCRIPT;
    static {
        LIKE_SCRIPT = new DefaultRedisScript<>();
        LIKE_SCRIPT.setLocation(new ClassPathResource("like.lua"));
        LIKE_SCRIPT.setResultType(Long.class);
    }

    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT;
    static {
        UNLIKE_SCRIPT = new DefaultRedisScript<>();
        UNLIKE_SCRIPT.setLocation(new ClassPathResource("unlike.lua"));
        UNLIKE_SCRIPT.setResultType(Long.class);
    }


    /**
     * 添加歌曲评论
     *
     * @param commentSongDTO 歌曲评论DTO
     * @return Result
     */
    @Override
    @Transactional
    public Result addSongComment(CommentSongDTO commentSongDTO) {
        Long userId = ThreadLocalUtil.getUserId();

        Comment comment = new Comment();
        comment.setUserId(userId).setSongId(commentSongDTO.getSongId())
                .setContent(commentSongDTO.getContent()).setType(0)
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L);

        if (commentMapper.insert(comment) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        // 在添加评论后
        stringRedisTemplate.delete(DETAIL_SONGIDS + commentSongDTO.getSongId());

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 添加歌单评论
     *
     * @param commentPlaylistDTO 歌单评论DTO
     * @return Result
     */
    @Override
    @Transactional

    public Result addPlaylistComment(CommentPlaylistDTO commentPlaylistDTO) {
        Long userId = ThreadLocalUtil.getUserId();

        Comment comment = new Comment();
        comment.setUserId(userId).setPlaylistId(commentPlaylistDTO.getPlaylistId())
                .setContent(commentPlaylistDTO.getContent()).setType(1)
                .setCreateTime(LocalDateTime.now()).setLikeCount(0L);

        if (commentMapper.insert(comment) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        stringRedisTemplate.delete(DETAIL_PLAYLISTIDS + commentPlaylistDTO.getPlaylistId());
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 点赞评论
     *
     * @param commentId 评论 ID
     * @return Result
     */
    @Override
    @Transactional
    public Result likeComment(Long commentId) {
        Long userId = ThreadLocalUtil.getUserId();
    
        String likeKey = COMMENT_LIKE + commentId;
        String countKey = COMMENT_LIKE_COUNT + commentId;
    
        // ⭐ 关键修复：从数据库获取当前点赞数（用于初始化 Redis 缓存）
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }

        // ⭐ 修复：处理 likeCount 可能为 null 的情况
        Long dbLikeCount = comment.getLikeCount();
        if (dbLikeCount == null) {
            dbLikeCount = 0L;
        }

        // 生成随机偏移量（0 ~ MAX_RANDOM_OFFSET 秒）
        long randomOffset = ThreadLocalRandom.current().nextLong(MAX_RANDOM_OFFSET + 1);
    
        // 执行 Lua 脚本（传递数据库的点赞数）
        Long result = stringRedisTemplate.execute(
                LIKE_SCRIPT,
                Arrays.asList(likeKey, countKey),
                userId.toString(),
                String.valueOf(BASE_EXPIRE_TIME),
                String.valueOf(randomOffset),
                String.valueOf(dbLikeCount)
        );
    
        if (result == null || result == 0) {
            return Result.error(MessageConstant.ALREADY_LIKED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 取消点赞评论
     *
     * @param commentId 评论 ID
     * @return Result
     */
    @Override
    public Result cancelLikeComment(Long commentId) {
        Long userId = ThreadLocalUtil.getUserId();
    
        String likeKey = COMMENT_LIKE + commentId;
        String countKey = COMMENT_LIKE_COUNT + commentId;

        // ⭐ 关键修复：从数据库获取当前点赞数（用于初始化 Redis 缓存）
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }

        // ⭐ 修复：处理 likeCount 可能为 null 的情况
        Long dbLikeCount = comment.getLikeCount();
        if (dbLikeCount == null) {
            dbLikeCount = 0L;
        }

        // 生成随机偏移量
        long randomOffset = ThreadLocalRandom.current().nextLong(MAX_RANDOM_OFFSET + 1);

        Long result = stringRedisTemplate.execute(
                UNLIKE_SCRIPT,
                Arrays.asList(likeKey, countKey),
                userId.toString(),
                String.valueOf(BASE_EXPIRE_TIME),
                String.valueOf(randomOffset),
                String.valueOf(dbLikeCount)
        );
        if (result == null || result == 0) {
            return Result.error(MessageConstant.NOT_LIKED);
        }
        return Result.success(MessageConstant.SUCCESS);
    }

    /**
     * 删除评论
     *
     * @param commentId 评论ID
     * @return Result
     */
    @Override
    @Transactional
    public Result deleteComment(Long commentId) {
        Long userId = ThreadLocalUtil.getUserId();

        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return Result.error(MessageConstant.NOT_FOUND);
        }
        //确保用户只能删除自己的评论
        if (!Objects.equals(comment.getUserId(), userId)) {
            return Result.error(MessageConstant.NO_PERMISSION);
        }

        if (commentMapper.deleteById(commentId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }
}
