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
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.*;

@Component
public class CacheHelper {

    private static final long LOCK_WAIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(LOCK_TTL_SECONDS);
    private static final long LOCK_RETRY_BASE_SLEEP_MILLIS = 20L;
    private static final int LOCK_RETRY_RANDOM_BOUND_MILLIS = 30;

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
     * 閫氱敤鎵归噺璇︽儏鑾峰彇妯℃澘
     * @param ids         ID 鍒楄〃
     * @param keyPrefix   Redis Key 鍓嶇紑 (濡?"song:info:")
     * @param clazz       VO 鐨勭被瀵硅薄 (濡?SongVO.class)
     * @param dbLookup    鍥炴簮鏌ヨ閫昏緫 (杈撳叆缂哄け鐨?ID 鍒楄〃锛岃緭鍑哄疄浣撳垪琛?
     * @param idExtractor 浠庡璞′腑鎻愬彇 ID 鐨勯€昏緫 (鐢ㄤ簬閲嶆柊寤虹珛鏄犲皠)
     */
    private <T> List<T> getDetailsBatchInternal(
            List<Long> ids,
            String keyPrefix,
            Class<T> clazz,
            Function<List<Long>, List<T>> dbLookup,
            Function<T, Long> idExtractor
    ) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();

        // 1. 灏濊瘯浠?Redis 鎵归噺鑾峰彇 (MultiGet)
        List<String> keys = ids.stream().map(id -> keyPrefix + id).toList();
        List<String> jsonList = stringRedisTemplate.opsForValue().multiGet(keys);

        Map<Long, T> resultMap = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        // 2. 瑙ｆ瀽缂撳瓨缁撴灉锛岃褰曠己澶?ID
        for (int i = 0; i < ids.size(); i++) {
            String json = (jsonList != null) ? jsonList.get(i) : null;
            if (json != null) {
                resultMap.put(ids.get(i), JSONUtil.toBean(json, clazz));
            } else {
                missingIds.add(ids.get(i));
            }
        }
        // 3. 鍥炴簮澶勭悊缂哄け鏁版嵁
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

            // 猸?闃茬┛閫忥紙娌℃煡鍒扮殑ID涔熺紦瀛橈級
            for (Long id : missingIds) {
                if (!dbIds.contains(id)) {
                    waitToCache.put(keyPrefix + id, "null");
                }
            }

            // 4. Pipeline 鎵归噺鍥炲啓 Redis锛屾墽琛?Redis 绠￠亾鎿嶄綔
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Map.Entry<String, String> entry : waitToCache.entrySet()) {
                    long ttl = DETAIL_CACHE_TTL_MINUTES +
                            ThreadLocalRandom.current().nextInt(DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES);
                    connection.setEx(entry.getKey().getBytes(StandardCharsets.UTF_8),
                            ttl * 60,
                            entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        }
        // 5. 鎸夌収鍘熷 ID 椤哄簭杩斿洖鍒楄〃 (骞惰繃婊ゆ帀鏁版嵁搴撻噷涔熸煡涓嶅埌鐨勬棤鏁?ID)
        return ids.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    /**
     * 鎵归噺鑾峰彇姝屾洸鍒楄〃
     * @param songIds 姝屾洸 ID 鍒楄〃
     * @return 姝屾洸璇︽儏鍒楄〃
     */
    public List<SongVO> getSongDetailsBatch(List<Long> songIds) {
        return getDetailsBatchInternal(
                songIds,
                ALL_SONGIDS,
                SongVO.class,
                songMapper::getSongsByIdS, // 鏂规硶寮曠敤鏇村ソ: songMapper::getSongsByIdS
                SongVO::getSongId
        );
    }

    /**
     * 鎵归噺鑾峰彇姝屽崟鍒楄〃
     * @param playlistIds 姝屽崟 ID 鍒楄〃
     * @return 姝屽崟璇︽儏鍒楄〃
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
     * 鎵归噺鑾峰彇姝屾墜璇︽儏锛堣蛋缂撳瓨锛?
     */
    public List<ArtistVO> getArtistDetailsBatch(List<Long> artistIds) {
        return getDetailsBatchInternal(
                artistIds,
                ALL_SINGERIDS,      // Redis Key 鍓嶇紑
                ArtistVO.class,
                artistMapper::getArtistsByIds, // 鏁版嵁搴撳洖婧愰€昏緫
                ArtistVO::getArtistId          // ID 鎻愬彇閫昏緫
        );
    }

    /**********************************************************************************************************************************/

    /**
     * 閫氱敤鐨勬敹钘忕紦瀛橀鐑柟娉曪紙鏀寔鏂规硶寮曠敤锛?
     * @param redisKey 缂撳瓨閿?
     * @param userId 鐢ㄦ埛 ID
     * @param dbLookup 鏁版嵁搴撴煡璇㈤€昏緫 (鍑芥暟寮忔帴鍙ｏ紝鎺ユ敹 userId 杩斿洖 List<Long>)
     */
    private void ensureFavoriteCacheInternal(String redisKey, Long userId,
                                             Function<Long, List<Long>> dbLookup) {
        // 1. 妫€鏌ョ紦瀛樻槸鍚﹀瓨鍦?
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey))) {
            return;
        }

        // 2. 鎵ц浼犲叆鐨勬煡璇㈤€昏緫
        List<Long> ids = dbLookup.apply(userId);

        // 3. 鍐欏叆 Redis
        if (ids != null && !ids.isEmpty()) {
            String[] idStrs = ids.stream()
                    .map(String::valueOf)
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(redisKey, idStrs);
        } else {
            // 闃叉缂撳瓨绌块€忥紝瀛樺叆鍗犱綅绗?
            stringRedisTemplate.opsForSet().add(redisKey, "-1");
        }

        // 4. 璁剧疆缁熶竴鐨勮繃鏈熸椂闂?
        stringRedisTemplate.expire(redisKey, FAVORITE_CACHE_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 纭繚鐢ㄦ埛鏀惰棌澶圭紦瀛樺湪 Redis 涓紙渚涙墍鏈夌浉鍏虫帴鍙ｈ皟鐢級
     */
    public void ensureFavoriteCache(Long userId) {
        String setKey = FAV_SONGIDS + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoriteSongIds);
    }

    /**
     * 纭繚鐢ㄦ埛鏀惰棌姝屽崟鍒楄〃缂撳瓨鍦?Redis 涓紙渚涙墍鏈夌浉鍏虫帴鍙ｈ皟鐢級
     */
    public void ensureFavoritePlaylistCache(Long userId) {
        String setKey = FAV_PLAYLIST + userId;
        ensureFavoriteCacheInternal(setKey, userId, userFavoriteMapper::getUserFavoritePlaylistIds);
    }

    /**********************************************************************************************************************************/

    /**
     * 鎵归噺澶勭悊璇勮鍒楄〃鐨勭偣璧炰俊鎭紙MGET + Pipeline 浼樺寲锛?
     * @param comments 璇勮鍒楄〃
     * @param userId   鐢ㄦ埛 ID锛堟敮鎸?null锛岃〃绀烘湭鐧诲綍锛?
     */
    public void processCommentLikes(List<CommentVO> comments, Long userId) {
        if (comments == null || comments.isEmpty()) {
            return;
        }

        // 1. MGET 鎵归噺鑾峰彇鎵€鏈夎瘎璁虹殑鐐硅禐鏁?
        List<String> likeCountKeys = comments.stream()
                .map(vo -> COMMENT_LIKE_COUNT + vo.getCommentId())
                .collect(Collectors.toList());

        List<String> countValues = stringRedisTemplate.opsForValue().multiGet(likeCountKeys);

        // 2. 鍥炲～鐐硅禐鏁?
        for (int i = 0; i < comments.size(); i++) {
            CommentVO vo = comments.get(i);
            String count = (countValues != null) ? countValues.get(i) : null;
            if (count != null) {
                vo.setLikeCount(Long.valueOf(count));
            }
        }

        // 3. Pipeline 鎵归噺妫€鏌ョ敤鎴风偣璧炵姸鎬?
        if (userId != null) {
            List<String> commentLikeKeys = new ArrayList<>();
            for (CommentVO vo : comments) {
                commentLikeKeys.add(COMMENT_LIKE + vo.getCommentId());
            }

            // 浣跨敤 Pipeline 鎵归噺鎵ц SISMEMBER 鍛戒护
            List<Object> pipelineResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] userIdBytes = userId.toString().getBytes(StandardCharsets.UTF_8);
                for (String key : commentLikeKeys) {
                    connection.sIsMember(key.getBytes(StandardCharsets.UTF_8), userIdBytes);
                }
                return null;
            });

            // 鍥炲～鐐硅禐鐘舵€?
            for (int i = 0; i < comments.size(); i++) {
                CommentVO vo = comments.get(i);
                Boolean isLiked = (Boolean) pipelineResults.get(i);
                vo.setIsLiked(isLiked != null ? isLiked : false);
            }
        } else {
            // 鏈櫥褰曠敤鎴烽粯璁?false
            for (CommentVO vo : comments) {
                vo.setIsLiked(false);
            }
        }
    }
    /**********************************************************************************************************************************/
    /**
     * 閫氱敤鐨勬壒閲忔鏌ユ敹钘忕姸鎬佹柟娉?
     * * @param items        鍒楄〃鏁版嵁 (濡?List<SongVO> 鎴?List<PlaylistVO>)
     * @param userId       鐢ㄦ埛 ID
     * @param redisKey     Redis 鐨?Key (濡?FAV_SONGIDS + userId)
     * @param cacheEnsurer 缂撳瓨棰勭儹閫昏緫 (Runnable)
     * @param idExtractor  濡備綍浠庡璞′腑鎻愬彇 ID (濡?SongVO::getSongId)
     * @param action       鍖归厤鍚庣殑鍔ㄤ綔 (濡?(song, liked) -> song.setLikeStatus(...))
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

        // 1. 纭繚缂撳瓨瀛樺湪
        cacheEnsurer.run();

        // 2. 鍑嗗鏌ヨ鐨?ID 鍒楄〃 (String 绫诲瀷)
        List<String> idStrList = items.stream()
                .map(idExtractor)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();

        // 3. 鎵归噺鏌ヨ Redis
        Map<Object, Boolean> isMemberMap = stringRedisTemplate.opsForSet()
                .isMember(redisKey, idStrList.toArray());

        // 4. 鎵ц鍥炶皟鍔ㄤ綔
        for (T item : items) {
            Long id = idExtractor.apply(item);
            boolean liked = Boolean.TRUE.equals(isMemberMap.get(id.toString()));
            action.accept(item, liked);
        }
    }

    /**
     * 鎵归噺妫€鏌ユ瓕鏇叉槸鍚﹁鐢ㄦ埛鏀惰棌
     * @param songs  姝屾洸鍒楄〃
     * @param userId 鐢ㄦ埛 ID
     */
    public void batchCheckSongLikeStatus(List<SongVO> songs, Long userId) {
        batchCheckInternal(
                songs,
                userId,
                FAV_SONGIDS + userId,
                () -> ensureFavoriteCache(userId), // 棰勭儹姝屾洸缂撳瓨
                SongVO::getSongId,                 // 鎻愬彇姝屾洸 ID
                (song, liked) -> song.setLikeStatus(liked
                        ? LikeStatusEnum.LIKE.getId()
                        : LikeStatusEnum.DEFAULT.getId()) // 璁剧疆鐘舵€?
        );
    }

    /**
     * 鎵归噺妫€鏌ユ瓕鍗曟槸鍚﹁鐢ㄦ埛鏀惰棌锛堢敤浜庢帹鑽愭瓕鍗曠瓑鍦烘櫙锛?
     * @param playlists 姝屽崟鍒楄〃
     * @param userId    鐢ㄦ埛 ID
     */
    public void batchCheckPlaylistStatus(List<PlaylistVO> playlists, Long userId) {
        batchCheckInternal(
                playlists,
                userId,
                FAV_PLAYLIST + userId,
                () -> ensureFavoritePlaylistCache(userId),
                PlaylistVO::getPlaylistId,
                (playlist, liked) -> { /* 鏆傛椂涓嶅啓浠讳綍閫昏緫锛屾垨鑰呬粎鍋氭棩蹇楄褰?*/ }
        );
    }

    /**********************************************************************************************************************************/
    /**
     * 单个 Key 查询，使用分布式锁防止缓存击穿。
     * 处理流程：
     * 1. 先查缓存，命中则直接返回；
     * 2. 未命中时尝试加锁，拿到锁的线程负责回源数据库并回填缓存；
     * 3. 未拿到锁的线程短暂休眠后重试，直到超时；
     * 4. 查询时间较长时通过轻量续期任务避免锁提前过期。
     */
    public <T> T getWithLock(
            String keyPrefix,      // Redis key 前缀
            Long id,               // 业务主键 ID
            Class<T> clazz,        // 目标对象类型，用于反序列化
            Function<Long, T> dbFallback  // 数据库回源查询函数
    ) {
        String key = keyPrefix + id;
        long deadline = System.currentTimeMillis() + LOCK_WAIT_TIMEOUT_MILLIS;// 锁超时时间

        while (true) {
            // 1. 先查缓存，命中后直接返回
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                if ("null".equals(json)) {
                    return null;
                }
                return JSONUtil.toBean(json, clazz);
            }

            // 2. 缓存未命中，尝试获取分布式锁
            String lockValue = redisLock.tryLock(key, LOCK_TTL_SECONDS);
            if (lockValue != null) {
                // 拿到锁后启动续期任务，避免长查询时锁提前过期
                ScheduledFuture<?> renewalTask = redisLock.scheduleRenewal(key, lockValue, LOCK_TTL_SECONDS);
                try {
                    // 3. Double Check，防止其他线程已经完成回填
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (json != null) {
                        if ("null".equals(json)) {
                            return null;
                        }
                        return JSONUtil.toBean(json, clazz);
                    }

                    // 4. 回源数据库
                    T data = dbFallback.apply(id);
                    if (data == null) {
                        // 写入空值，避免缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "null", NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                        return null;
                    }

                    // 5. 回填缓存，并附带随机 TTL 避免同一时间大量失效
                    long ttl = DETAIL_CACHE_TTL_MINUTES +
                            ThreadLocalRandom.current().nextInt(DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES);
                    stringRedisTemplate.opsForValue().set(
                            key,
                            JSONUtil.toJsonStr(data),
                            ttl,
                            TimeUnit.MINUTES
                    );
                    return data;
                } finally {
                    if (renewalTask != null) {
                        renewalTask.cancel(false);
                    }
                    redisLock.unlock(key, lockValue);
                }
            }

            // 6. 没拿到锁，等待超时后再查一次缓存，仍未命中则直接降级查库返回
            if (System.currentTimeMillis() >= deadline) {
                json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    if ("null".equals(json)) {
                        return null;
                    }
                    return JSONUtil.toBean(json, clazz);
                }
                return dbFallback.apply(id);
            }

            try {
                // 7. 短暂休眠并随机退避，降低高并发下的竞争压力
                long sleepMillis = LOCK_RETRY_BASE_SLEEP_MILLIS
                        + ThreadLocalRandom.current().nextInt(LOCK_RETRY_RANDOM_BOUND_MILLIS);
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                // 保留中断标记，交由上层决定后续处理
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
