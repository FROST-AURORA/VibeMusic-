package cn.edu.seig.vibemusic.util;


import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.CommentVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.*;

@Component
public class CacheHelper {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private SongMapper songMapper;
    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private RedisLock redisLock;

    /**
     * 通用批量详情获取模板
     * @param ids         ID 列表
     * @param keyPrefix   Redis Key 前缀 (如 "song:info:")
     * @param clazz       VO 的类对象 (如 SongVO.class)
     * @param dbLookup    回源查询逻辑 (输入缺失的 ID 列表，输出实体列表)
     * @param idExtractor 从对象中提取 ID 的逻辑 (用于重新建立映射)
     */
    private <T> List<T> getDetailsBatchInternal(
            List<Long> ids,
            String keyPrefix,
            Class<T> clazz,
            Function<List<Long>, List<T>> dbLookup,
            Function<T, Long> idExtractor
    ) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();

        // 1. 尝试从 Redis 批量获取 (MultiGet)
        List<String> keys = ids.stream().map(id -> keyPrefix + id).toList();
        List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(keys);

        Map<Long, T> resultMap = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        // 2. 解析缓存结果，记录缺失 ID
        for (int i = 0; i < ids.size(); i++) {
            String json = (jsonList != null) ? jsonList.get(i) : null;
            if (json != null) {
                resultMap.put(ids.get(i), JSONUtil.toBean(json, clazz));
            } else {
                missingIds.add(ids.get(i));
            }
        }
        // 3. 回源处理缺失数据
        if (!missingIds.isEmpty()) {
            List<T> dbResults = dbLookup.apply(missingIds);

            Map<String, String> waitToCache = new HashMap<>();
            Set<Long> dbIds = new HashSet<>();

            if (dbResults != null) {
                for (T item : dbResults) {
                    Long id = idExtractor.apply(item);
                    dbIds.add(id);
                    resultMap.put(id, item);
                    waitToCache.put(keyPrefix + id, JSONUtil.toJsonStr(item));
                }
            }

            // ⭐ 防穿透（没查到的ID也缓存）
            for (Long id : missingIds) {
                if (!dbIds.contains(id)) {
                    waitToCache.put(keyPrefix + id, "null");
                }
            }

            // 4. Pipeline 批量回写 Redis，执行 Redis 管道操作
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Map.Entry<String, String> entry : waitToCache.entrySet()) {
                    long ttl = 60L + ThreadLocalRandom.current().nextInt(30);
                    connection.setEx(entry.getKey().getBytes(StandardCharsets.UTF_8),
                            ttl * 60,
                            entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        }
        // 5. 按照原始 ID 顺序返回列表 (并过滤掉数据库里也查不到的无效 ID)
        return ids.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    /**
     * 批量获取歌曲列表
     * @param songIds 歌曲 ID 列表
     * @return 歌曲详情列表
     */
    public List<SongVO> getSongDetailsBatch(List<Long> songIds) {
        return getDetailsBatchInternal(
                songIds,
                ALL_SONGIDS,
                SongVO.class,
                songMapper::getSongsByIdS, // 方法引用更好: songMapper::getSongsByIdS
                SongVO::getSongId
        );
    }

    /**
     * 批量获取歌单列表
     * @param playlistIds 歌单 ID 列表
     * @return 歌单详情列表
     */
    public List<PlaylistVO> getPlaylistDetailsBatch(List<Long> playlistIds) {
        return getDetailsBatchInternal(
                playlistIds,
                ALL_PLAYLISTIDS,
                PlaylistVO.class,
                playlistMapper::getPlaylistDetailsByIds,
                PlaylistVO::getPlaylistId
        );
    }

    /**
     * 批量获取歌手详情（走缓存）
     */
    public List<ArtistVO> getArtistDetailsBatch(List<Long> artistIds) {
        return getDetailsBatchInternal(
                artistIds,
                ALL_SINGERIDS,      // Redis Key 前缀
                ArtistVO.class,
                artistMapper::getArtistsByIds, // 数据库回源逻辑
                ArtistVO::getArtistId          // ID 提取逻辑
        );
    }

    /**********************************************************************************************************************************/

    /**
     * 通用的收藏缓存预热方法（支持方法引用）
     * @param redisKey 缓存键
     * @param userId 用户 ID
     * @param dbLookup 数据库查询逻辑 (函数式接口，接收 userId 返回 List<Long>)
     */
    private void ensureFavoriteCacheInternal(String redisKey, Long userId,
                                             Function<Long, List<Long>> dbLookup) {
        // 1. 检查缓存是否存在
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey))) {
            return;
        }

        // 2. 执行传入的查询逻辑
        List<Long> ids = dbLookup.apply(userId);

        // 3. 写入 Redis
        if (ids != null && !ids.isEmpty()) {
            String[] idStrs = ids.stream()
                    .map(String::valueOf)
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(redisKey, idStrs);
        } else {
            // 防止缓存穿透，存入占位符
            stringRedisTemplate.opsForSet().add(redisKey, "-1");
        }

        // 4. 设置统一的过期时间
        stringRedisTemplate.expire(redisKey, 24, TimeUnit.HOURS);
    }

    /**
     * 确保用户收藏夹缓存在 Redis 中（供所有相关接口调用）
     */
    public void ensureFavoriteCache(Long userId) {
        String setKey = FAV_SONGIDS + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoriteSongIds);
    }

    /**
     * 确保用户收藏歌单列表缓存在 Redis 中（供所有相关接口调用）
     */
    public void ensureFavoritePlaylistCache(Long userId) {
        String setKey = FAV_PLAYLIST + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoritePlaylistIds);
    }

    /**********************************************************************************************************************************/

    /**
     * 批量处理评论列表的点赞信息（MGET + Pipeline 优化）
     * @param comments 评论列表
     * @param userId   用户 ID（支持 null，表示未登录）
     */
    public void processCommentLikes(List<CommentVO> comments, Long userId) {
        if (comments == null || comments.isEmpty()) {
            return;
        }

        // 1. MGET 批量获取所有评论的点赞数
        List<String> likeCountKeys = comments.stream()
                .map(vo -> COMMENT_LIKE_COUNT + vo.getCommentId())
                .collect(Collectors.toList());
        
        List<String> countValues = stringRedisTemplate.opsForValue().multiGet(likeCountKeys);
        
        // 2. 回填点赞数
        for (int i = 0; i < comments.size(); i++) {
            CommentVO vo = comments.get(i);
            String count = (countValues != null) ? countValues.get(i) : null;
            if (count != null) {
                vo.setLikeCount(Long.valueOf(count));
            }
        }
        
        // 3. Pipeline 批量检查用户点赞状态
        if (userId != null) {
            List<String> commentLikeKeys = new ArrayList<>();
            for (CommentVO vo : comments) {
                commentLikeKeys.add(COMMENT_LIKE + vo.getCommentId());
            }
            
            // 使用 Pipeline 批量执行 SISMEMBER 命令
            List<Object> pipelineResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] userIdBytes = userId.toString().getBytes(StandardCharsets.UTF_8);
                for (String key : commentLikeKeys) {
                    connection.sIsMember(key.getBytes(StandardCharsets.UTF_8), userIdBytes);
                }
                return null;
            });
            
            // 回填点赞状态
            for (int i = 0; i < comments.size(); i++) {
                CommentVO vo = comments.get(i);
                Boolean isLiked = (Boolean) pipelineResults.get(i);
                vo.setIsLiked(isLiked != null ? isLiked : false);
            }
        } else {
            // 未登录用户默认 false
            for (CommentVO vo : comments) {
                vo.setIsLiked(false);
            }
        }
    }
    /**********************************************************************************************************************************/
    /**
     * 通用的批量检查收藏状态方法
     * * @param items        列表数据 (如 List<SongVO> 或 List<PlaylistVO>)
     * @param userId       用户 ID
     * @param redisKey     Redis 的 Key (如 FAV_SONGIDS + userId)
     * @param cacheEnsurer 缓存预热逻辑 (Runnable)
     * @param idExtractor  如何从对象中提取 ID (如 SongVO::getSongId)
     * @param action       匹配后的动作 (如 (song, liked) -> song.setLikeStatus(...))
     */
    private <T> void batchCheckInternal(
            List<T> items,
            Long userId,
            String redisKey,
            Runnable cacheEnsurer,
            Function<T, Long> idExtractor,
            BiConsumer<T, Boolean> action
    ) {
        if (userId == null || items == null || items.isEmpty()) {
            return;
        }

        // 1. 确保缓存存在
        cacheEnsurer.run();

        // 2. 准备查询的 ID 列表 (String 类型)
        List<String> idStrList = items.stream()
                .map(idExtractor)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();

        // 3. 批量查询 Redis
        Map<Object, Boolean> isMemberMap = stringRedisTemplate.opsForSet()
                .isMember(redisKey, idStrList.toArray());

        // 4. 执行回调动作
        for (T item : items) {
            Long id = idExtractor.apply(item);
            boolean liked = Boolean.TRUE.equals(isMemberMap.get(id.toString()));
            action.accept(item, liked);
        }
    }

    /**
     * 批量检查歌曲是否被用户收藏
     * @param songs  歌曲列表
     * @param userId 用户 ID
     */
    public void batchCheckSongLikeStatus(List<SongVO> songs, Long userId) {
        batchCheckInternal(
                songs,
                userId,
                FAV_SONGIDS + userId,
                () -> ensureFavoriteCache(userId), // 预热歌曲缓存
                SongVO::getSongId,                 // 提取歌曲 ID
                (song, liked) -> song.setLikeStatus(liked
                        ? LikeStatusEnum.LIKE.getId()
                        : LikeStatusEnum.DEFAULT.getId()) // 设置状态
        );
    }

    /**
     * 批量检查歌单是否被用户收藏（用于推荐歌单等场景）
     * @param playlists 歌单列表
     * @param userId    用户 ID
     */
    public void batchCheckPlaylistStatus(List<PlaylistVO> playlists, Long userId) {
        batchCheckInternal(
                playlists,
                userId,
                FAV_PLAYLIST + userId,
                () -> ensureFavoritePlaylistCache(userId),
                PlaylistVO::getPlaylistId,
                (playlist, liked) -> { /* 暂时不写任何逻辑，或者仅做日志记录 */ }
        );
    }

    /**********************************************************************************************************************************/
    //单个 Key 查询（核心防击穿）
    public <T> T getWithLock(
            String keyPrefix,      // Redis key 前缀
            Long id,               // 业务主键 ID
            Class<T> clazz,        // 目标类型，用于反序列化
            Function<Long, T> dbFallback  // 数据库查询函数（Lambda）
    ) {
        String key = keyPrefix + id;
        // 1️⃣ 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 命中
        if (json != null) {
            // 防穿透（缓存空值）
            if ("null".equals(json)) { //检查缓存的值是不是字符串 "null"
                return null;
            }
            return JSONUtil.toBean(json, clazz);
        }
        String lockValue = redisLock.tryLock(key, 10);
        // 2️⃣ 尝试加锁
        if (lockValue!= null) {
            try {
                // 3️⃣ Double Check
                json = stringRedisTemplate.opsForValue().get(key);
                // 命中，有值
                if (json != null) {
                    if ("null".equals(json)) return null;
                    return JSONUtil.toBean(json, clazz);
                }
                // 4️⃣ 查数据库
                T data = dbFallback.apply(id);
                if (data == null) {
                    // ⭐ 防穿透
                    stringRedisTemplate.opsForValue().set(key, "null", 5, TimeUnit.MINUTES);
                    return null;
                }
                // 5️⃣ 写缓存（随机TTL防雪崩）
                long ttl = 60 + ThreadLocalRandom.current().nextInt(30);
                stringRedisTemplate.opsForValue().set(
                        key,
                        JSONUtil.toJsonStr(data),
                        ttl,
                        TimeUnit.MINUTES
                );
                return data;
            } finally {
                redisLock.unlock(key, lockValue);
            }
        } else {
            // 6️⃣ 自旋重试
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {}
            return getWithLock(keyPrefix, id, clazz, dbFallback);
        }
    }
}
