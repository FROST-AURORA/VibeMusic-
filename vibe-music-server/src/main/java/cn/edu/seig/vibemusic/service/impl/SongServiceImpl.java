package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.enumeration.LikeStatusEnum;
import cn.edu.seig.vibemusic.mapper.*;
import cn.edu.seig.vibemusic.model.dto.SongAddDTO;
import cn.edu.seig.vibemusic.model.dto.SongAndArtistDTO;
import cn.edu.seig.vibemusic.model.dto.SongDTO;
import cn.edu.seig.vibemusic.model.dto.SongUpdateDTO;
import cn.edu.seig.vibemusic.model.entity.Genre;
import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.entity.Style;
import cn.edu.seig.vibemusic.model.vo.CommentVO;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISongService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
@Slf4j
@Service
@CacheConfig(cacheNames = "songCache")
public class SongServiceImpl extends ServiceImpl<SongMapper, Song> implements ISongService {

    @Autowired
    private SongMapper songMapper;
    @Autowired
    private UserFavoriteMapper userFavoriteMapper;
    @Autowired
    private StyleMapper styleMapper;
    @Autowired
    private GenreMapper genreMapper;
    @Autowired
    private MinioService minioService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheHelper cacheHelper;
    @Autowired
    private CacheInvalidationHelper cacheInvalidationHelper;
    @Autowired
    private CommentMapper commentMapper;

    /**
     * 获取所有歌曲
     *
     * @param songDTO songDTO
     * @return 歌曲列表
     */
    @Override
    public Result<PageResult<SongVO>> getAllSongs(SongDTO songDTO, HttpServletRequest request) {
        // 使用 JwtUtil 工具类获取用户 ID（支持弱登录）
        Long userId = JwtUtil.getUserIdFromRequest(request);
        // 1.查 ID 分页
        Page<Long> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        IPage<Long> idPage = songMapper.querySongIds(
                page,
                songDTO.getSongName(),
                songDTO.getArtistName(),
                songDTO.getAlbum());
        List<Long> songIds = idPage.getRecords();// 要返回的歌曲id
        if (songIds.isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        // 2. 复用 CacheHelper 批量获取歌曲详情
        List<SongVO> finalOrderedList = cacheHelper.getSongDetailsBatch(songIds);

        // 3. 批量处理个性化状态 (关键优化：SMISMEMBER)
        cacheHelper.batchCheckSongLikeStatus(finalOrderedList, userId);
        return Result.success(new PageResult<>(idPage.getTotal(), finalOrderedList));
    }


    /**
     * 获取推荐歌曲
     * 推荐歌曲的数量为 20
     *
     * @param request HttpServletRequest，用于获取请求头中的 token
     * @return 推荐歌曲列表
     */
    @Override
    public Result<List<SongVO>> getRecommendedSongs(HttpServletRequest request) {
        // 使用 JwtUtil 工具类获取用户 ID（支持弱登录）
        Long userId = JwtUtil.getUserIdFromRequest(request);

        // 1. 未登录处理：直接复用随机逻辑，但建议走详情缓存
        if (userId == null) {
            List<Long> randomIds = songMapper.getRandomSongIds(20); // 仅查 ID
            return Result.success(cacheHelper.getSongDetailsBatch(randomIds));
        }

        String redisKey = RECOMMEND_SONGIDS + userId;
        // 2. 从 Redis 获取缓存的推荐 ID 列表 (只存 ID)
        String jsonStr = stringRedisTemplate.opsForValue().get(redisKey);
        List<Long> cachedSongIds = JSONUtil.toList(jsonStr, Long.class);

        if (cachedSongIds == null || cachedSongIds.isEmpty()) {
            // 3. 计算风格偏好 & 回源查询 ID
            List<Long> favoriteSongIds = userFavoriteMapper.getFavoriteSongIdsByUserId(userId);
            if (favoriteSongIds.isEmpty()) {
                return Result.success(cacheHelper.getSongDetailsBatch(songMapper.getRandomSongIds(20)));
            }

            // 统计频率（这一步可以保留在内存，也可以考虑下沉到 SQL）
            List<Long> favoriteStyleIds = songMapper.getFavoriteSongStyles(favoriteSongIds);
            List<Long> sortedStyleIds = favoriteStyleIds.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                    .entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            // 数据库仅返回符合风格的歌曲 ID（排除已收藏的歌）
            cachedSongIds = songMapper.getRecommendedSongIdsByStyles(sortedStyleIds, favoriteSongIds, 80);

            // 缓存 ID 列表 30 分钟
            long randomTtl = RECOMMEND_SONG_TTL_MINUTES +
                    ThreadLocalRandom.current().nextInt(RECOMMEND_SONG_TTL_RANDOM_BOUND_MINUTES);
            stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(cachedSongIds), randomTtl, TimeUnit.MINUTES);
        }

        // 4. 洗牌并截取 20 个 ID
        List<Long> targetIds = new ArrayList<>(cachedSongIds);
        Collections.shuffle(targetIds);//把列表中的歌曲 ID 顺序彻底打乱
        List<Long> resultIds = targetIds.subList(0, Math.min(20, targetIds.size()));

        // 5. 补足逻辑
        if (resultIds.size() < 20) {
            List<Long> extraIds = songMapper.getRandomSongIds(40); // 获取一批随机 ID
            Set<Long> existing = new HashSet<>(resultIds);
            for (Long id : extraIds) {
                if (resultIds.size() >= 20) break;
                if (!existing.contains(id)) resultIds.add(id);
            }
        }

        // 6. 统一走“详情缓存”获取 VO，并批量处理点赞状态
        List<SongVO> finalSongs = cacheHelper.getSongDetailsBatch(resultIds);
        // 注意：推荐里的歌用户可能也点过赞（虽然排除掉收藏夹，但用户可能刚点完赞缓存没更新）
        // 这里建议调用你 getAllSongs 里的“批量点赞状态查询”逻辑(已经封装在CacheHelper方法中了)
        cacheHelper.batchCheckSongLikeStatus(finalSongs, userId);

        return Result.success(finalSongs);

    }

    /**
     * 获取歌曲详情
     *
     * @param songId  歌曲id
     * @param request HttpServletRequest，用于获取请求头中的 token
     * @return 歌曲详情
     */
    @Override
    public Result<SongDetailVO> getSongDetail(Long songId, HttpServletRequest request) {
        // 1. 获取用户 ID（支持弱登录）
        Long userId = JwtUtil.getUserIdFromRequest(request);

        // 2. 使用新的 getWithLock 方法查询缓存（带分布式锁防击穿）
        SongDetailVO songDetailVO = cacheHelper.getWithLock(
                DETAIL_SONGIDS,
                songId,
                SongDetailVO.class,
                songMapper::getSongDetailById
        );
        // 数据库也没找到，返回错误
        if (songDetailVO == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }
        
        // 3. 获取评论列表并处理点赞信息（使用封装的方法）
        List<CommentVO> comments = commentMapper.getCommentListBySongId(songId);
        if (comments != null && !comments.isEmpty()) {
            cacheHelper.processCommentLikes(comments, userId);
            songDetailVO.setComments(comments);
        }
        
        // 4. 如果用户已登录，检查收藏状态（使用 Redis Set）
        if (userId != null) {
            String favKey = FAV_SONGIDS + userId;
            Boolean isLiked = stringRedisTemplate.opsForSet().isMember(favKey, songId.toString());
            songDetailVO.setLikeStatus(Boolean.TRUE.equals(isLiked)
                    ? LikeStatusEnum.LIKE.getId()
                    : LikeStatusEnum.DEFAULT.getId());
        }
        return Result.success(songDetailVO);
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 获取歌手的所有歌曲
     *
     * @param songDTO songAndArtistDTO
     * @return 歌曲列表
     */
    @Override
    @Cacheable(key = "#songDTO.pageNum + '-' + #songDTO.pageSize + '-' + #songDTO.songName + '-' + #songDTO.album + '-' + #songDTO.artistId + '-admin-v2'")
    public Result<PageResult<SongAdminVO>> getAllSongsByArtist(SongAndArtistDTO songDTO) {
        // 分页查询
        Page<SongAdminVO> page = new Page<>(songDTO.getPageNum(), songDTO.getPageSize());
        IPage<SongAdminVO> songPage = songMapper.getSongsWithArtistName(page, songDTO.getArtistId(), songDTO.getSongName(), songDTO.getAlbum());

        if (songPage.getRecords().isEmpty()) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        return Result.success(new PageResult<>(songPage.getTotal(), songPage.getRecords()));
    }


    /**
     * 获取所有歌曲的数量
     *
     * @param style 歌曲风格
     * @return 歌曲数量
     */
    @Override
    public Result<Long> getAllSongsCount(String style) {
        QueryWrapper<Song> queryWrapper = new QueryWrapper<>();
        if (style != null) {
            queryWrapper.like("style", style);
        }

        return Result.success(songMapper.selectCount(queryWrapper));
    }

    /**
     * 添加歌曲信息
     *
     * @param songAddDTO 歌曲信息
     * @return 结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result addSong(SongAddDTO songAddDTO) {
        Song song = new Song();
        BeanUtils.copyProperties(songAddDTO, song);

        // 插入歌曲记录
        if (songMapper.insert(song) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }

        // 获取刚插入的歌曲记录
        Song songInDB = songMapper.selectOne(new QueryWrapper<Song>()
                .eq("artist_id", songAddDTO.getArtistId())
                .eq("name", songAddDTO.getSongName())
                .eq("album", songAddDTO.getAlbum())
                .orderByDesc("id")
                .last("LIMIT 1"));//用于在生成的 SQL 语句末尾追加自定义的 SQL 片段

        if (songInDB == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }

        Long songId = songInDB.getSongId();

        // 解析风格字段（多个风格以逗号分隔）
        String styleStr = songAddDTO.getStyle();
        if (styleStr != null && !styleStr.isEmpty()) {
            //数组转为固定大小的 List, "流行,摇滚" → ["流行","摇滚"]
            List<String> styles = Arrays.asList(styleStr.split(","));

            // 查询风格 ID
            List<Style> styleList = styleMapper.selectList(new QueryWrapper<Style>().in("name", styles));

            // 插入到 tb_genre(歌曲对应风格表)
            for (Style style : styleList) {
                Genre genre = new Genre();
                genre.setSongId(songId);
                genre.setStyleId(style.getStyleId());
                genreMapper.insert(genre);
            }
        }

        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }


    /**
     * 更新歌曲信息
     *
     * @param songUpdateDTO 歌曲信息
     * @return 结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result updateSong(SongUpdateDTO songUpdateDTO) {
        // 查询数据库中是否存在该歌曲
        Song songInDB = songMapper.selectById(songUpdateDTO.getSongId());
        if (songInDB == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }

        // 更新歌曲基本信息
        Song song = new Song();
        BeanUtils.copyProperties(songUpdateDTO, song);
        if (songMapper.updateById(song) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        Long songId = songUpdateDTO.getSongId();

        // 删除 tb_genre 中该歌曲的原有风格映射
        genreMapper.delete(new QueryWrapper<Genre>().eq("song_id", songId));

        // 解析新的风格字段（多个风格以逗号分隔）
        String styleStr = songUpdateDTO.getStyle();
        if (styleStr != null && !styleStr.isEmpty()) {
            List<String> styles = Arrays.asList(styleStr.split(","));

            // 查询风格 ID
            List<Style> styleList = styleMapper.selectList(new QueryWrapper<Style>().in("name", styles));

            // 插入新的风格映射到 tb_genre
            for (Style style : styleList) {
                Genre genre = new Genre();
                genre.setSongId(songId);
                genre.setStyleId(style.getStyleId());
                genreMapper.insert(genre);
            }
        }

        cacheInvalidationHelper.evictSongCache(songId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌曲封面
     *
     * @param songId   歌曲id
     * @param coverUrl 封面url
     * @return 更新结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result updateSongCover(Long songId, String coverUrl) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }
        String cover = song.getCoverUrl();
        if (cover != null && !cover.isEmpty()) {
            minioService.deleteFile(cover);
        }

        song.setCoverUrl(coverUrl);
        if (songMapper.updateById(song) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        cacheInvalidationHelper.evictSongCache(songId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 更新歌曲音频
     *
     * @param songId   歌曲id
     * @param audioUrl 音频url
     * @return 更新结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result updateSongAudio(Long songId, String audioUrl, String duration) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }
        String audio = song.getAudioUrl();
        if (audio != null && !audio.isEmpty()) {
            minioService.deleteFile(audio);
        }

        song.setAudioUrl(audioUrl).setDuration(duration);
        if (songMapper.updateById(song) == 0) {
            return Result.error(MessageConstant.UPDATE + MessageConstant.FAILED);
        }

        cacheInvalidationHelper.evictSongCache(songId);

        return Result.success(MessageConstant.UPDATE + MessageConstant.SUCCESS);
    }

    /**
     * 删除歌曲
     *
     * @param songId 歌曲id
     * @return 删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteSong(Long songId) {
        Song song = songMapper.selectById(songId);
        if (song == null) {
            return Result.error(MessageConstant.SONG + MessageConstant.NOT_FOUND);
        }
        String cover = song.getCoverUrl();
        String audio = song.getAudioUrl();

        if (cover != null && !cover.isEmpty()) {
            minioService.deleteFile(cover);
        }
        if (audio != null && !audio.isEmpty()) {
            minioService.deleteFile(audio);
        }

        if (songMapper.deleteById(songId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        cacheInvalidationHelper.evictSongCache(songId);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除歌曲
     *
     * @param songIds 歌曲id列表
     * @return 删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "songCache", allEntries = true)
    public Result deleteSongs(List<Long> songIds) {
        // 1. 查询歌曲信息，获取歌曲封面 URL 列表
        List<Song> songs = songMapper.selectByIds(songIds);
        List<String> coverUrlList = songs.stream()
                .map(Song::getCoverUrl)
                .filter(coverUrl -> coverUrl != null && !coverUrl.isEmpty())
                .toList();
        List<String> audioUrlList = songs.stream()
                .map(Song::getAudioUrl)
                .filter(audioUrl -> audioUrl != null && !audioUrl.isEmpty())
                .toList();

        // 2. 先删除 MinIO 里的歌曲封面和音频文件
        for (String coverUrl : coverUrlList) {
            minioService.deleteFile(coverUrl);
        }
        for (String audioUrl : audioUrlList) {
            minioService.deleteFile(audioUrl);
        }

        // 3. 删除数据库中的歌曲信息
        if (songMapper.deleteByIds(songIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }

        cacheInvalidationHelper.evictSongCache(songIds);

        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

}
