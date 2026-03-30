package cn.edu.seig.vibemusic.helper;

import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.CommentVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.util.RedisLock;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_PLAYLISTIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_SINGERIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_SONGIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.COMMENT_LIKE;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.COMMENT_LIKE_COUNT;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.DETAIL_CACHE_TTL_MINUTES;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.FAVORITE_CACHE_TTL_HOURS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.FAV_PLAYLIST;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.FAV_SONGIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.LOCK_TTL_SECONDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.NULL_CACHE_TTL_MINUTES;

@Component
public class CacheHelper {

    private static final long LOCK_WAIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(LOCK_TTL_SECONDS);
    private static final long LOCK_RETRY_BASE_SLEEP_MILLIS = 20L;
    private static final int LOCK_RETRY_RANDOM_BOUND_MILLIS = 30;
    private static final String NULL_VALUE = "null";

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
     * 批量获取详情数据。
     * 先通过 MultiGet 查询 Redis，未命中的部分再批量回源数据库，
     * 最后使用 Pipeline 回填缓存，并为不存在的数据写入空值占位。
     */
    private <T> List<T> getDetailsBatchInternal(
            List<Long> ids,
            String keyPrefix,
            Class<T> clazz,
            Function<List<Long>, List<T>> dbLookup,
            Function<T, Long> idExtractor
    ) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> keys = ids.stream().map(id -> keyPrefix + id).toList();
        List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(keys);

        Map<Long, T> resultMap = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();

        for (int i = 0; i < ids.size(); i++) {
            String json = jsonList != null ? jsonList.get(i) : null;
            if (json == null) {
                missingIds.add(ids.get(i));
                continue;
            }
            if (!NULL_VALUE.equals(json)) {
                resultMap.put(ids.get(i), JSONUtil.toBean(json, clazz));
            }
        }

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

            for (Long id : missingIds) {
                if (!dbIds.contains(id)) {
                    waitToCache.put(keyPrefix + id, NULL_VALUE);
                }
            }

            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Map.Entry<String, String> entry : waitToCache.entrySet()) {
                    long ttl = DETAIL_CACHE_TTL_MINUTES
                            + ThreadLocalRandom.current().nextInt(DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES);
                    connection.setEx(
                            entry.getKey().getBytes(StandardCharsets.UTF_8),
                            ttl * 60,
                            entry.getValue().getBytes(StandardCharsets.UTF_8)
                    );
                }
                return null;
            });
        }

        return ids.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 批量获取歌曲详情。
     */
    public List<SongVO> getSongDetailsBatch(List<Long> songIds) {
        return getDetailsBatchInternal(
                songIds,
                ALL_SONGIDS,
                SongVO.class,
                songMapper::getSongsByIdS,
                SongVO::getSongId
        );
    }

    /**
     * 批量获取歌单详情。
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
     * 批量获取歌手详情。
     */
    public List<ArtistVO> getArtistDetailsBatch(List<Long> artistIds) {
        return getDetailsBatchInternal(
                artistIds,
                ALL_SINGERIDS,
                ArtistVO.class,
                artistMapper::getArtistsByIds,
                ArtistVO::getArtistId
        );
    }

    /**
     * 预热用户收藏缓存。
     * 如果缓存不存在，则从数据库加载收藏 ID 集合并写入 Redis。
     * 空集合会写入占位值，避免缓存穿透。
     */
    private void ensureFavoriteCacheInternal(
            String redisKey,
            Long userId,
            Function<Long, List<Long>> dbLookup
    ) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey))) {
            return;
        }

        List<Long> ids = dbLookup.apply(userId);
        if (ids != null && !ids.isEmpty()) {
            String[] idStrs = ids.stream()
                    .map(String::valueOf)
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(redisKey, idStrs);
        } else {
            stringRedisTemplate.opsForSet().add(redisKey, "-1");
        }

        stringRedisTemplate.expire(redisKey, FAVORITE_CACHE_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 预热用户收藏歌曲缓存。
     */
    public void ensureFavoriteCache(Long userId) {
        String setKey = FAV_SONGIDS + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoriteSongIds);
    }

    /**
     * 预热用户收藏歌单缓存。
     */
    public void ensureFavoritePlaylistCache(Long userId) {
        String setKey = FAV_PLAYLIST + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoritePlaylistIds);
    }

    /**
     * 批量处理评论点赞信息。
     * 使用 MGET 读取点赞数，使用 Pipeline 批量判断当前用户是否点过赞。
     */
    public void processCommentLikes(List<CommentVO> comments, Long userId) {
        if (comments == null || comments.isEmpty()) {
            return;
        }

        List<String> likeCountKeys = comments.stream()
                .map(vo -> COMMENT_LIKE_COUNT + vo.getCommentId())
                .collect(Collectors.toList());

        List<String> countValues = stringRedisTemplate.opsForValue().multiGet(likeCountKeys);
        for (int i = 0; i < comments.size(); i++) {
            CommentVO vo = comments.get(i);
            String count = countValues != null ? countValues.get(i) : null;
            if (count != null) {
                vo.setLikeCount(Long.valueOf(count));
            }
        }

        if (userId != null) {
            List<String> commentLikeKeys = comments.stream()
                    .map(vo -> COMMENT_LIKE + vo.getCommentId())
                    .toList();

            List<Object> pipelineResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] userIdBytes = userId.toString().getBytes(StandardCharsets.UTF_8);
                for (String key : commentLikeKeys) {
                    connection.sIsMember(key.getBytes(StandardCharsets.UTF_8), userIdBytes);
                }
                return null;
            });

            for (int i = 0; i < comments.size(); i++) {
                CommentVO vo = comments.get(i);
                Boolean isLiked = (Boolean) pipelineResults.get(i);
                vo.setIsLiked(isLiked != null && isLiked);
            }
        } else {
            for (CommentVO vo : comments) {
                vo.setIsLiked(false);
            }
        }
    }

    /**
     * 批量检查点赞或收藏状态。
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

        cacheEnsurer.run();

        List<String> idStrList = items.stream()
                .map(idExtractor)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();

        Map<Object, Boolean> isMemberMap = stringRedisTemplate.opsForSet()
                .isMember(redisKey, idStrList.toArray());

        for (T item : items) {
            Long id = idExtractor.apply(item);
            boolean liked = Boolean.TRUE.equals(isMemberMap.get(id.toString()));
            action.accept(item, liked);
        }
    }

    /**
     * 批量检查歌曲收藏状态。
     */
    public void batchCheckSongLikeStatus(List<SongVO> songs, Long userId) {
        batchCheckInternal(
                songs,
                userId,
                FAV_SONGIDS + userId,
                () -> ensureFavoriteCache(userId),
                SongVO::getSongId,
                (song, liked) -> song.setLikeStatus(
                        liked ? LikeStatusEnum.LIKE.getId() : LikeStatusEnum.DEFAULT.getId()
                )
        );
    }

    /**
     * 批量检查歌单收藏状态。
     * 当前歌单列表对象未使用该状态字段，这里保留统一处理入口。
     */
    public void batchCheckPlaylistStatus(List<PlaylistVO> playlists, Long userId) {
        batchCheckInternal(
                playlists,
                userId,
                FAV_PLAYLIST + userId,
                () -> ensureFavoritePlaylistCache(userId),
                PlaylistVO::getPlaylistId,
                (playlist, liked) -> {
                }
        );
    }

    /**
     * 单个 Key 查询，使用分布式互斥锁防止缓存击穿。
     * 处理流程：
     * 1. 先查缓存，命中则直接返回。
     * 2. 未命中时尝试获取分布式锁。
     * 3. 拿到锁的线程负责回源数据库并回填缓存。
     * 4. 未拿到锁的线程短暂休眠后重试，直到超过等待时间。
     * 5. 超时后仅再检查一次缓存，不再直接回源数据库，避免并发打穿数据库。
     */
    public <T> T getWithLock(
            String keyPrefix,
            Long id,
            Class<T> clazz,
            Function<Long, T> dbFallback
    ) {
        String key = keyPrefix + id;
        // 等待窗口只覆盖抢锁重试阶段，避免请求无限阻塞。
        long deadline = System.currentTimeMillis() + LOCK_WAIT_TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            // 先查缓存，命中普通值或空值占位都直接返回。
            T cachedValue = getCacheValue(key, clazz);
            if (cachedValue != null || Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return cachedValue;
            }

            // 只有拿到锁的线程负责回源数据库，其余线程等待缓存重建完成。
            String lockValue = redisLock.tryLock(key, LOCK_TTL_SECONDS);
            if (lockValue != null) {
                try {
                    // Double check：避免拿锁前已有其他线程完成回填。
                    cachedValue = getCacheValue(key, clazz);
                    if (cachedValue != null || Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                        return cachedValue;
                    }

                    // 缓存仍未命中时，才真正执行数据库查询。
                    T data = dbFallback.apply(id);
                    if (data == null) {
                        // 写入空值占位，避免不存在的数据被反复击穿。
                        stringRedisTemplate.opsForValue().set(
                                key,
                                NULL_VALUE,
                                NULL_CACHE_TTL_MINUTES,
                                TimeUnit.MINUTES
                        );
                        return null;
                    }

                    // 正常数据使用随机 TTL，降低同一批 key 同时过期的风险。
                    long ttl = DETAIL_CACHE_TTL_MINUTES
                            + ThreadLocalRandom.current().nextInt(DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES);
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
            }

            try {
                // 未拿到锁时短暂随机退避，给持锁线程留出回填时间。
                long sleepMillis = LOCK_RETRY_BASE_SLEEP_MILLIS
                        + ThreadLocalRandom.current().nextInt(LOCK_RETRY_RANDOM_BOUND_MILLIS);
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        // 超时后只再查一次缓存，不直接回源数据库，避免并发打穿 DB。
        return getCacheValue(key, clazz);
    }

    /**
     * 统一处理普通缓存值和空值占位。
     */
    private <T> T getCacheValue(String key, Class<T> clazz) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        if (NULL_VALUE.equals(json)) {
            return null;
        }
        return JSONUtil.toBean(json, clazz);
    }
}
