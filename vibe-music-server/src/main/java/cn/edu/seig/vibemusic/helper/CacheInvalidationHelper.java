package cn.edu.seig.vibemusic.helper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_PLAYLISTIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_SINGERIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.ALL_SONGIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.DETAIL_PLAYLISTIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.DETAIL_SINGERIDS;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.DETAIL_SONGIDS;

@Slf4j
@Component
public class CacheInvalidationHelper {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 删除歌曲缓存
     * @param songId 歌曲ID
     */
    public void evictSongCache(Long songId) {
        if (songId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(List.of(
                    ALL_SONGIDS + songId,
                    DETAIL_SONGIDS + songId
            ));
        } catch (Exception e) {
            log.warn("删除歌曲 Redis 缓存失败, songId={}", songId, e);
        }
    }

    public void evictSongCache(List<Long> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>(songIds.size() * 2);
            for (Long songId : songIds) {
                if (songId == null) {
                    continue;
                }
                keys.add(ALL_SONGIDS + songId);
                keys.add(DETAIL_SONGIDS + songId);
            }
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("批量删除歌曲 Redis 缓存失败, songIds={}", songIds, e);
        }
    }

    /**
     * 删除歌手缓存
     * @param artistId 歌手ID
     */
    public void evictArtistCache(Long artistId) {
        if (artistId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(List.of(
                    ALL_SINGERIDS + artistId,
                    DETAIL_SINGERIDS + artistId
            ));
        } catch (Exception e) {
            log.warn("鍒犻櫎姝屾墜 Redis 缂撳瓨澶辫触, artistId={}", artistId, e);
        }
    }

    public void evictArtistCache(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>(artistIds.size() * 2);
            for (Long artistId : artistIds) {
                if (artistId == null) {
                    continue;
                }
                keys.add(ALL_SINGERIDS + artistId);
                keys.add(DETAIL_SINGERIDS + artistId);
            }
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("鎵归噺鍒犻櫎姝屾墜 Redis 缂撳瓨澶辫触, artistIds={}", artistIds, e);
        }
    }

    /**
     * 删除歌单缓存
     * @param playlistId 歌单ID
     */
    public void evictPlaylistCache(Long playlistId) {
        if (playlistId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(List.of(
                    ALL_PLAYLISTIDS + playlistId,
                    DETAIL_PLAYLISTIDS + playlistId
            ));
        } catch (Exception e) {
            log.warn("鍒犻櫎姝屽崟 Redis 缂撳瓨澶辫触, playlistId={}", playlistId, e);
        }
    }

    public void evictPlaylistCache(List<Long> playlistIds) {
        if (playlistIds == null || playlistIds.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>(playlistIds.size() * 2);
            for (Long playlistId : playlistIds) {
                if (playlistId == null) {
                    continue;
                }
                keys.add(ALL_PLAYLISTIDS + playlistId);
                keys.add(DETAIL_PLAYLISTIDS + playlistId);
            }
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("鎵归噺鍒犻櫎姝屽崟 Redis 缂撳瓨澶辫触, playlistIds={}", playlistIds, e);
        }
    }
}
