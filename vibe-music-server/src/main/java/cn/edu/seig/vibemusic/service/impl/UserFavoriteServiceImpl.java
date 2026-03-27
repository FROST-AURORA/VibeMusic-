package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.SongDTO;
import cn.edu.seig.vibemusic.model.entity.UserFavorite;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IUserFavoriteService;
import cn.edu.seig.vibemusic.helper.CacheHelper;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static cn.edu.seig.vibemusic.constant.RsdisConstants.FAV_PLAYLIST;
import static cn.edu.seig.vibemusic.constant.RsdisConstants.FAV_SONGIDS;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@CacheConfig(cacheNames = "userFavoriteCache")
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements IUserFavoriteService {

    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private SongMapper songMapper;
    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheHelper cacheHelper;

    /**
     * 获取用户收藏的歌曲列表
     *
     * @param songDTO 歌曲查询条件
     * @return 用户收藏的歌曲列表
     */
    @Override
    public Result<PageResult<SongVO>> getUserFavoriteSongs(SongDTO songDTO) {
        Long userId = ThreadLocalUtil.getUserId();
        // 1. 核心优化：确保 Redis 缓存是热的
        // 这个方法会保证 Redis 中至少有一个占位符或真实的 ID
        cacheHelper.ensureFavoriteCache(userId);
        
        // 1. 分页查询收藏夹中的歌曲 ID（这一步只查 ID，SQL 压力极小）
        Page<Long> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        // 注意：这个 mapper 方法需要你新写或修改，只返回歌曲 ID
        IPage<Long> favoriteIdPage = userFavoriteMapper.queryFavoriteSongIds(
                page,
                userId,
                songDTO.getSongName(),
                songDTO.getArtistName(),
                songDTO.getAlbum());

        List<Long> songIds = favoriteIdPage.getRecords();
        if (songIds.isEmpty()) {
            return Result.success(new PageResult<>(0L, Collections.emptyList()));
        }
        // 2. 复用“批量获取歌曲详情”的逻辑（核心优化点：走缓存）
        List<SongVO> songVOList = cacheHelper.getSongDetailsBatch(songIds);
        // 3. 因为是收藏夹，LikeStatus 肯定是已收藏
        songVOList.forEach(vo -> vo.setLikeStatus(LikeStatusEnum.LIKE.getId()));
        return Result.success(new PageResult<>(favoriteIdPage.getTotal(), songVOList));
    }



    /**
     * 收藏歌曲
     *
     * @param songId 歌曲 ID
     * @return 成功或失败
     */
    @Override
    @Transactional
    public Result collectSong(Long songId) {
        Long userId = ThreadLocalUtil.getUserId();
        String setKey = FAV_SONGIDS + userId;

        // 1. 数据库去重检查 (也可以利用数据库唯一索引来捕捉异常)
        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 0).eq("song_id", songId);
        if (userFavoriteMapper.selectCount(queryWrapper) > 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }

        // 2. 写入数据库
        UserFavorite userFavorite = new UserFavorite();
        userFavorite.setUserId(userId).setType(0).setSongId(songId).setCreateTime(LocalDateTime.now());
        userFavoriteMapper.insert(userFavorite);

        // 3. 精准更新 Redis 缓存 (不再使用 CacheEvict 整个清空)
        // 如果缓存存在，才进行更新，保证数据一致性
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey))) {
            // 如果原本是占位符 -1，先移除它
            stringRedisTemplate.opsForSet().remove(setKey, "-1");
            // 添加新的收藏 ID
            stringRedisTemplate.opsForSet().add(setKey, songId.toString());
        }

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 取消收藏歌曲
     *
     * @param songId 歌曲 ID
     * @return 成功或失败
     */
    @Override
    @Transactional
    public Result cancelCollectSong(Long songId) {
        Long userId = ThreadLocalUtil.getUserId();
        String setKey = FAV_SONGIDS + userId;

        // 1. 数据库删除
        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 0).eq("song_id", songId);
        if (userFavoriteMapper.delete(queryWrapper) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        // 2. 精准更新 Redis 缓存
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey))) {
            stringRedisTemplate.opsForSet().remove(setKey, songId.toString());

            // 如果移除后集合空了，补一个占位符 -1，防止缓存穿透
            Long size = stringRedisTemplate.opsForSet().size(setKey);
            if (size == null || size == 0) {
                stringRedisTemplate.opsForSet().add(setKey, "-1");
            }
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**********************************************************************************************************************/
    /**
     * 获取用户收藏的歌单列表
     *
     * @param playlistDTO 歌单查询条件
     * @return 用户收藏的歌单列表
     */
    @Override
    public Result<PageResult<PlaylistVO>> getUserFavoritePlaylists(PlaylistDTO playlistDTO) {
        Long userId = ThreadLocalUtil.getUserId();

        // 1. 保证 Redis 里的收藏 ID 集合是热的（虽然本接口暂不用它判断状态，但为了系统一致性建议保留）
        cacheHelper.ensureFavoritePlaylistCache(userId);

        // 2. 分页查 ID：在 user_favorite 和 playlist 表关联查询符合条件的 ID
        Page<Long> page = new Page<>(playlistDTO.getPageNum(), playlistDTO.getPageSize());
        IPage<Long> idPage = userFavoriteMapper.queryFavoritePlaylistIds(
                page,
                userId,
                playlistDTO.getTitle(),
                playlistDTO.getStyle()
        );

        List<Long> ids = idPage.getRecords();
        if (ids.isEmpty()) {
            return Result.success(new PageResult<>(0L, Collections.emptyList()));
        }

        // 3. 批量获取详情（建议内部实现同样使用 Redis MultiGet 优化）
        List<PlaylistVO> playlistVOList = cacheHelper.getPlaylistDetailsBatch(ids);

        // 4. 直接返回，无需处理 LikeStatus
        return Result.success(new PageResult<>(idPage.getTotal(), playlistVOList));
    }

    /**
     * 收藏歌单
     *
     * @param playlistId 歌单 ID
     * @return 成功或失败
     */
    @Override
    @Transactional
    public Result collectPlaylist(Long playlistId) {
        Long userId = ThreadLocalUtil.getUserId();
        String setKey = FAV_PLAYLIST + userId;

        // 1. 检查是否已收藏
        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 1).eq("playlist_id", playlistId);
        if (userFavoriteMapper.selectCount(queryWrapper) > 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }

        // 2. 数据库插入
        UserFavorite userFavorite = new UserFavorite();
        userFavorite.setUserId(userId).setType(1).setPlaylistId(playlistId).setCreateTime(LocalDateTime.now());
        userFavoriteMapper.insert(userFavorite);

        // 3. 精准更新 Redis 缓存
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey))) {
            // 如果原本是占位符 -1，先移除它
            stringRedisTemplate.opsForSet().remove(setKey, "-1");
            // 添加新的收藏 ID
            stringRedisTemplate.opsForSet().add(setKey, playlistId.toString());
        }

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 取消收藏歌单
     *
     * @param playlistId 歌单 ID
     * @return 成功或失败
     */
    @Override
    @Transactional
    public Result cancelCollectPlaylist(Long playlistId) {
        Long userId = ThreadLocalUtil.getUserId();
        String setKey = FAV_PLAYLIST + userId;

        // 1. 数据库删除
        QueryWrapper<UserFavorite> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("type", 1).eq("playlist_id", playlistId);
        if (userFavoriteMapper.delete(queryWrapper) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        // 2. 精准更新 Redis 缓存
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(setKey))) {
            stringRedisTemplate.opsForSet().remove(setKey, playlistId.toString());

            // 如果移除后集合空了，补一个占位符 -1，防止缓存穿透
            Long size = stringRedisTemplate.opsForSet().size(setKey);
            if (size == null || size == 0) {
                stringRedisTemplate.opsForSet().add(setKey, "-1");
            }
        }

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
