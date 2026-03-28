package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.CommentMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.mapper.UserFavoriteMapper;
import cn.edu.seig.vibemusic.model.dto.PlaylistAddDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistDTO;
import cn.edu.seig.vibemusic.model.dto.PlaylistUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.vo.CommentVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IPlaylistService;
import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.helper.CacheHelper;
import cn.edu.seig.vibemusic.helper.CacheInvalidationHelper;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

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
@CacheConfig(cacheNames = "playlistCache")
public class PlaylistServiceImpl extends ServiceImpl<PlaylistMapper, Playlist> implements IPlaylistService {

    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private MinioService minioService;
    @Autowired
    private CacheHelper cacheHelper;
    @Autowired
    private CacheInvalidationHelper cacheInvalidationHelper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CommentMapper commentMapper;

    /**
     * 获取所有歌单
     *
     * @param playlistDTO playlistDTO
     * @return 歌单列表
     */
    @Override
    public Result<PageResult<PlaylistVO>> getAllPlaylists(PlaylistDTO playlistDTO) {

        // 1. 查 ID 分页
        Page<Long> page = new Page<>(playlistDTO.getPageNum(), playlistDTO.getPageSize());
        IPage<Long> idPage = playlistMapper.queryPlaylistIds(
                page,
                playlistDTO.getTitle(),
                playlistDTO.getStyle());
        List<Long> playlistIds = idPage.getRecords();
        
        if (playlistIds.isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        // 2. 复用 CacheHelper 批量获取歌单详情
        List<PlaylistVO> finalOrderedList = cacheHelper.getPlaylistDetailsBatch(playlistIds);

        return Result.success(new PageResult<>(idPage.getTotal(), finalOrderedList));
    }



    /**
     * 获取推荐歌单
     * 推荐歌单的数量为 10
     *
     * @param request HttpServletRequest，用于获取请求头中的 token
     * @return 随机歌单列表
     */
    @Override
    public Result<List<PlaylistVO>> getRecommendedPlaylists(HttpServletRequest request) {
        // 1. 获取用户 ID
        Long userId = JwtUtil.getUserIdFromRequest(request);

        // 2. 未登录处理：随机 ID -> 批量详情缓存
        if (userId == null) {
            List<Long> randomIds = playlistMapper.getRandomPlaylistIds(10);
            return Result.success(cacheHelper.getPlaylistDetailsBatch(randomIds));
        }

        String redisKey = RECOMMEND_PLAYLISTIDS + userId;

        // 3. 尝试从 Redis 获取推荐 ID 列表
        String jsonStr = stringRedisTemplate.opsForValue().get(redisKey);
        List<Long> cachedPlaylistIds = JSONUtil.toList(jsonStr, Long.class);

        if (cachedPlaylistIds == null || cachedPlaylistIds.isEmpty()) {
            // 4. 回源计算：获取收藏列表 -> 分析风格偏好
            List<Long> favoriteIds = userFavoriteMapper.getFavoritePlaylistIdsByUserId(userId);

            if (favoriteIds.isEmpty()) {
                // 无收藏记录，返回随机
                List<Long> randomIds = playlistMapper.getRandomPlaylistIds(10);
                return Result.success(cacheHelper.getPlaylistDetailsBatch(randomIds));
            }

            // 统计风格频率并排序 (假设风格依然通过 ID 管理)
            List<Long> favoriteStyleIds = playlistMapper.getFavoritePlaylistStyleIds(favoriteIds);
            List<Long> sortedStyleIds = favoriteStyleIds.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            // 5. 数据库查询推荐 ID（排除已收藏，取 40 个作为候选池）
            cachedPlaylistIds = playlistMapper.getRecommendedPlaylistsIdsByStyles(sortedStyleIds, favoriteIds, 40);

            // 6. 缓存推荐候选集 30 分钟
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    JSONUtil.toJsonStr(cachedPlaylistIds),
                    RECOMMEND_PLAYLIST_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
        }

        // 7. 洗牌并截取 10 个 ID
        List<Long> targetIds = new ArrayList<>(cachedPlaylistIds);
        Collections.shuffle(targetIds);
        List<Long> resultIds = new ArrayList<>(targetIds.subList(0, Math.min(10, targetIds.size())));

        // 8. 补足逻辑：如果推荐不足 10 个，用随机 ID 填充
        if (resultIds.size() < 10) {
            List<Long> extraIds = playlistMapper.getRandomPlaylistIds(20);
            Set<Long> existing = new HashSet<>(resultIds);
            for (Long id : extraIds) {
                if (resultIds.size() >= 10) break;
                if (!existing.contains(id)) resultIds.add(id);
            }
        }

        // 9. 批量装配 VO 详情 (走详情缓存)
        List<PlaylistVO> finalPlaylists = cacheHelper.getPlaylistDetailsBatch(resultIds);

        // 10. 全自动状态检查
        // 虽然歌单可能不需要显示 LikeStatus，但调用此方法是安全的
        // 它会识别出 PlaylistVO 类型并自动跳过 Redis 查询，不会产生额外开销
        cacheHelper.batchCheckPlaylistStatus(finalPlaylists, userId);

        return Result.success(finalPlaylists);
    }

    /**
     * 获取歌单详情
     *
     * @param playlistId 歌单 id
     * @param request    HttpServletRequest，用于获取请求头中的 token
     * @return 歌单详情
     */
    @Override
    public Result<PlaylistDetailVO> getPlaylistDetail(Long playlistId, HttpServletRequest request) {
        // 1. 获取用户 ID（支持弱登录）
        Long userId = JwtUtil.getUserIdFromRequest(request);
    
        // 2. 使用新的 getWithLock 方法查询缓存（带分布式锁防击穿）
        PlaylistDetailVO playlistDetailVO = cacheHelper.getWithLock(
                DETAIL_PLAYLISTIDS,
                playlistId,
                PlaylistDetailVO.class,
                playlistMapper::getPlaylistDetailById
        );
    
        // 数据库也没找到，返回错误
        if (playlistDetailVO == null) {
            return Result.error(MessageConstant.PLAYLIST + MessageConstant.NOT_FOUND);
        }
        
        // 3. 获取评论列表并处理点赞信息（使用封装的方法）
        List<CommentVO> comments = commentMapper.getCommentListByPlaylistId(playlistId);
        if (comments != null && !comments.isEmpty()) {
            cacheHelper.processCommentLikes(comments, userId);
            playlistDetailVO.setComments(comments);
        }
        
        // 4. 如果用户已登录，检查收藏状态（使用 Redis Set）
        if (userId != null) {
            // 4.1 检查歌单的收藏状态
            cacheHelper.ensureFavoritePlaylistCache(userId);//缓存预热
            String favPlaylistKey = FAV_PLAYLIST + userId;
            Boolean isPlaylistLiked = stringRedisTemplate.opsForSet().isMember(favPlaylistKey, playlistId.toString());
            playlistDetailVO.setLikeStatus(Boolean.TRUE.equals(isPlaylistLiked)
                    ? LikeStatusEnum.LIKE.getId()
                    : LikeStatusEnum.DEFAULT.getId());
    
            // 4.2 批量检查歌曲的收藏状态
            List<SongVO> songVOList = playlistDetailVO.getSongs();
            if (songVOList != null && !songVOList.isEmpty()) {
                cacheHelper.batchCheckSongLikeStatus(songVOList, userId);
            }
        } else {
            // 未登录用户，设置默认状态
            playlistDetailVO.setLikeStatus(LikeStatusEnum.DEFAULT.getId());
            List<SongVO> songVOList = playlistDetailVO.getSongs();
            if (songVOList != null) {
                songVOList.forEach(songVO -> songVO.setLikeStatus(LikeStatusEnum.DEFAULT.getId()));
            }
        }
    
        return Result.success(playlistDetailVO);
    }
    /**************************************************************************************************************************/
    /**
     * 获取所有歌单信息
     *
     * @param playlistDTO playlistDTO
     * @return 歌单列表
     */
    @Override
    @Cacheable(key = "#playlistDTO.pageNum + '-' + #playlistDTO.pageSize + '-' + #playlistDTO.title + '-' + #playlistDTO.style + '-admin'")
    public Result<PageResult<Playlist>> getAllPlaylistsInfo(PlaylistDTO playlistDTO) {
        // 分页查询
        Page<Playlist> page = new Page<>(playlistDTO.getPageNum(), playlistDTO.getPageSize());
        QueryWrapper<Playlist> queryWrapper = new QueryWrapper<>();
        // 根据 playlistDTO 的条件构建查询条件
        if (playlistDTO.getTitle() != null) {
            queryWrapper.like("title", playlistDTO.getTitle());
        }
        if (playlistDTO.getStyle() != null) {
            queryWrapper.eq("style", playlistDTO.getStyle());
        }
        // 倒序排序
        queryWrapper.orderByDesc("id");

        IPage<Playlist> playlistPage = playlistMapper.selectPage(page, queryWrapper);
        if (playlistPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        return Result.success(new PageResult<>(playlistPage.getTotal(), playlistPage.getRecords()));
    }

    /**
     * 获取所有歌单数量
     *
     * @param style 歌单风格
     * @return 歌单数量
     */
    @Override
    public Result<Long> getAllPlaylistsCount(String style) {
        QueryWrapper<Playlist> queryWrapper = new QueryWrapper<>();
        if (style != null) {
            queryWrapper.eq("style", style);
        }

        return Result.success(playlistMapper.selectCount(queryWrapper));
    }

    /**
     * 添加歌单
     *
     * @param playlistAddDTOO 歌单DTO
     * @return 添加结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result addPlaylist(PlaylistAddDTO playlistAddDTOO) {
        QueryWrapper<Playlist> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("title", playlistAddDTOO.getTitle());
        if (playlistMapper.selectCount(queryWrapper) > 0) {
            return Result.error(MessageConstant.PLAYLIST + MessageConstant.ALREADY_EXISTS);
        }

        Playlist playlist = new Playlist();
        BeanUtils.copyProperties(playlistAddDTOO, playlist);
        playlistMapper.insert(playlist);

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌单
     *
     * @param playlistUpdateDTO 歌单更新DTO
     * @return 更新结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result updatePlaylist(PlaylistUpdateDTO playlistUpdateDTO) {
        Long playlistId = playlistUpdateDTO.getPlaylistId();

        Playlist playlistByTitle = playlistMapper.selectOne(new QueryWrapper<Playlist>().eq("title", playlistUpdateDTO.getTitle()));
        if (playlistByTitle != null && !playlistByTitle.getPlaylistId().equals(playlistId)) {
            return Result.error(MessageConstant.PLAYLIST + MessageConstant.ALREADY_EXISTS);
        }

        Playlist playlist = new Playlist();
        BeanUtils.copyProperties(playlistUpdateDTO, playlist);
        if (playlistMapper.updateById(playlist) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        cacheInvalidationHelper.evictPlaylistCache(playlistId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌单封面
     *
     * @param playlistId 歌单id
     * @param coverUrl   歌单封面url
     * @return 更新结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result updatePlaylistCover(Long playlistId, String coverUrl) {
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist == null) {
            return Result.error(MessageConstant.PLAYLIST + MessageConstant.NOT_FOUND);
        }
        String cover = playlist.getCoverUrl();
        if (cover != null && !cover.isEmpty()) {
            minioService.deleteFile(cover);
        }

        playlist.setCoverUrl(coverUrl);
        if (playlistMapper.updateById(playlist) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }
        cacheInvalidationHelper.evictPlaylistCache(playlistId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 删除歌单
     *
     * @param playlistId 歌单id
     * @return 删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result deletePlaylist(Long playlistId) {
        // 1. 查询歌单信息，获取封面 URL
        Playlist playlist = playlistMapper.selectById(playlistId);
        if (playlist == null) {
            return Result.error(MessageConstant.PLAYLIST + MessageConstant.NOT_FOUND);
        }
        String coverUrl = playlist.getCoverUrl();

        // 2. 先删除 MinIO 里的封面文件
        if (coverUrl != null && !coverUrl.isEmpty()) {
            minioService.deleteFile(coverUrl);
        }

        // 3. 删除数据库中的歌单信息
        if (playlistMapper.deleteById(playlistId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        cacheInvalidationHelper.evictPlaylistCache(playlistId);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除歌单
     *
     * @param playlistIds 歌单id列表
     * @return 删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "playlistCache", allEntries = true)
    public Result deletePlaylists(List<Long> playlistIds) {
        List<Playlist> playlists = playlistMapper.selectBatchIds(playlistIds);
        List<String> coverUrlList = playlists.stream()
                .map(Playlist::getCoverUrl)
                .filter(coverUrl -> coverUrl != null && !coverUrl.isEmpty())
                .toList();

        // 2. 先删除 MinIO 里的封面文件
        for (String coverUrl : coverUrlList) {
            minioService.deleteFile(coverUrl);
        }

        // 3. 删除数据库中的歌单信息
        if (playlistMapper.deleteBatchIds(playlistIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        cacheInvalidationHelper.evictPlaylistCache(playlistIds);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
