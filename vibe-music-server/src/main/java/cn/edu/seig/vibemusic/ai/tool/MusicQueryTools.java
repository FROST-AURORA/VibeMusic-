package cn.edu.seig.vibemusic.ai.tool;

import cn.edu.seig.vibemusic.ai.model.MusicSearchCriteria;
import cn.edu.seig.vibemusic.helper.CacheHelper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.SongMapper;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISongService;
import cn.edu.seig.vibemusic.util.JwtUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 这里先放“只读查询工具”，后续可继续升级为真正的 Tool Calling。
 */
@Component
@RequiredArgsConstructor
public class MusicQueryTools {

    private final SongMapper songMapper;
    private final ArtistMapper artistMapper;
    private final CacheHelper cacheHelper;
    private final ISongService songService;

    /**
     * 搜索入口优先按 AI 解析出的风格做筛选。
     * 如果 AI 没提取出有效条件，则退回你现有的搜索逻辑。
     */
    public PageResult<SongVO> searchSongs(MusicSearchCriteria criteria, HttpServletRequest request) {
        //1. 解析 artistName
        List<Long> artistIds = resolveArtistIds(criteria.getArtistName());
        if (artistIds != null && artistIds.isEmpty()) {
            return new PageResult<>(0L, Collections.emptyList());
        }
        //2. 按 AI 解析出的风格做筛选
        Page<Long> page = new Page<>(criteria.getPageNum(), criteria.getPageSize());
        IPage<Long> idPage = songMapper.querySongIdsForAi(
                page,
                criteria.getSongName(),
                artistIds,
                criteria.getAlbum(),
                criteria.getStyleNames()
        );
        if (CollectionUtils.isEmpty(idPage.getRecords())
                && !StringUtils.hasText(criteria.getArtistName())
                && StringUtils.hasText(criteria.getSongName())) {
            List<Long> fallbackArtistIds = resolveArtistIds(criteria.getSongName());
            if (!CollectionUtils.isEmpty(fallbackArtistIds)) {
                idPage = songMapper.querySongIdsForAi(
                        page,
                        null,
                        fallbackArtistIds,
                        criteria.getAlbum(),
                        criteria.getStyleNames()
                );
            }
        }
        //3. 返回结果
        List<Long> songIds = idPage.getRecords();
        if (CollectionUtils.isEmpty(songIds)) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        List<SongVO> songs = cacheHelper.getSongDetailsBatch(songIds);
        //4. 批量检查是否已收藏
        Long userId = JwtUtil.getUserIdFromRequest(request);
        cacheHelper.batchCheckSongLikeStatus(songs, userId);
        return new PageResult<>(idPage.getTotal(), songs);
    }

    /**
     * 推荐入口优先按 AI 解析出的风格做筛选。
     * 如果 AI 没提取出有效条件，则退回你现有的推荐逻辑。
     */
    public List<SongVO> recommendSongs(MusicSearchCriteria criteria, HttpServletRequest request) {
        //只要有一个不为空，hasAiConditions 就是 true
        boolean hasAiConditions = !CollectionUtils.isEmpty(criteria.getStyleNames())
                || StringUtils.hasText(criteria.getSongName())
                || StringUtils.hasText(criteria.getArtistName())
                || StringUtils.hasText(criteria.getAlbum());
        //如果有 AI 条件，先尝试按条件搜索
        if (hasAiConditions) {
            MusicSearchCriteria searchCriteria = new MusicSearchCriteria();
            searchCriteria.setSongName(criteria.getSongName());
            searchCriteria.setArtistName(criteria.getArtistName());
            searchCriteria.setAlbum(criteria.getAlbum());
            searchCriteria.setStyleNames(criteria.getStyleNames());
            searchCriteria.setPageNum(1);
            searchCriteria.setPageSize(criteria.getLimit());
            PageResult<SongVO> pageResult = searchSongs(searchCriteria, request);
            if (!CollectionUtils.isEmpty(pageResult.getItems())) {
                return pageResult.getItems();
            }
        }
        //如果没有 AI 条件或搜索失败，用默认推荐
        Result<List<SongVO>> result = songService.getRecommendedSongs(request);
        return result.getData() == null ? Collections.emptyList() : result.getData();
    }

    /**
     * 尝试解析 artistName，并返回 artistId。
     * 如果 artistName 无效，则返回 null。
     */
    private List<Long> resolveArtistIds(String artistName) {
        if (!StringUtils.hasText(artistName)) {
            return null;
        }
        return artistMapper.getArtistIdsByNamePrefix(artistName);
    }
}
