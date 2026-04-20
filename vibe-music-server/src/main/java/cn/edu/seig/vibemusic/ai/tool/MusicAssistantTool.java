package cn.edu.seig.vibemusic.ai.tool;

import cn.edu.seig.vibemusic.ai.model.MusicIntent;
import cn.edu.seig.vibemusic.ai.model.MusicSearchCriteria;
import cn.edu.seig.vibemusic.helper.CacheHelper;
import cn.edu.seig.vibemusic.mapper.ArtistMapper;
import cn.edu.seig.vibemusic.mapper.PlaylistMapper;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import cn.edu.seig.vibemusic.result.PageResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MusicAssistantTool {

    @Autowired
    private MusicQueryTools musicQueryTools;
    @Autowired
    private ArtistMapper artistMapper;
    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private CacheHelper cacheHelper;

    @Tool("根据歌曲名、歌手名、专辑名或风格查询曲库中的歌曲，并返回精简结果摘要")
    public String searchSongs(
            @P("用户输入的歌曲关键词，可以是歌名、歌手、专辑或一句模糊描述") String keyword
    ) {
        MusicSearchCriteria criteria = new MusicSearchCriteria();
        criteria.setIntent(MusicIntent.SEARCH_SONG);
        criteria.setSongName(keyword);
        criteria.setPageNum(1);
        criteria.setPageSize(5);
        criteria.setLimit(5);

        PageResult<SongVO> pageResult = musicQueryTools.searchSongs(criteria, null);
        return formatSongs("歌曲查询结果", pageResult == null ? Collections.emptyList() : pageResult.getItems());
    }

    @Tool("根据歌手名查询平台中的歌手，以及该歌手的代表歌曲")
    public String findArtistSongs(
            @P("歌手名称，支持模糊匹配") String artistName
    ) {
        if (!StringUtils.hasText(artistName)) {
            return "歌手名为空，无法查询。";
        }

        List<Long> artistIds = artistMapper.getArtistIdsByNamePrefix(artistName.trim());
        if (CollectionUtils.isEmpty(artistIds)) {
            return "没有找到匹配的歌手。";
        }

        List<ArtistVO> artists = cacheHelper.getArtistDetailsBatch(artistIds.stream().limit(3).toList());
        MusicSearchCriteria criteria = new MusicSearchCriteria();
        criteria.setIntent(MusicIntent.SEARCH_SONG);
        criteria.setArtistName(artistName.trim());
        criteria.setPageNum(1);
        criteria.setPageSize(5);
        criteria.setLimit(5);

        PageResult<SongVO> pageResult = musicQueryTools.searchSongs(criteria, null);
        // 格式化歌手信息
        String artistSummary = artists.stream()
                .map(artist -> artist.getArtistId() + " - " + artist.getArtistName())
                .collect(Collectors.joining("\n"));
        return """
                匹配歌手:
                %s

                %s
                """.formatted(
                artistSummary,
                formatSongs("该歌手相关歌曲", pageResult == null ? Collections.emptyList() : pageResult.getItems())
        ).trim();
    }

    @Tool("根据风格或需求推荐歌曲，优先结合现有曲库和推荐逻辑")
    public String recommendSongs(
            @P("推荐需求，例如 轻音乐、适合学习、晚上听、节奏快") String requirement
    ) {
        MusicSearchCriteria criteria = new MusicSearchCriteria();
        criteria.setIntent(MusicIntent.RECOMMEND_SONG);
        criteria.setSongName(requirement);
        criteria.setPageNum(1);
        criteria.setPageSize(6);
        criteria.setLimit(6);

        List<SongVO> songs = musicQueryTools.recommendSongs(criteria, null);
        return formatSongs("歌曲推荐结果", songs);
    }

    @Tool("根据歌单标题或风格查询歌单")
    public String searchPlaylists(
            @P("歌单标题关键词，可为空") String title,
            @P("歌单风格关键词，可为空") String style
    ) {
        Page<Long> page = new Page<>(1, 5);
        // 按 AI 解析出的风格做筛选
        IPage<Long> playlistIdPage = playlistMapper.queryPlaylistIds(
                page,
                StringUtils.hasText(title) ? title.trim() : null,
                StringUtils.hasText(style) ? style.trim() : null
        );
        List<Long> playlistIds = playlistIdPage == null ? Collections.emptyList() : playlistIdPage.getRecords();
        if (CollectionUtils.isEmpty(playlistIds)) {
            return "没有找到匹配的歌单。";
        }

        List<PlaylistVO> playlists = cacheHelper.getPlaylistDetailsBatch(playlistIds);
        String content = playlists.stream()
                .map(playlist -> playlist.getPlaylistId() + " - " + playlist.getTitle())
                .collect(Collectors.joining("\n"));
        return """
                共找到 %d 个匹配歌单，当前展示前 %d 个:
                %s
                """.formatted(playlistIdPage.getTotal(), playlists.size(), content).trim();
    }

    /**
     * 格式化歌曲列表
     * @param title // 结果的标题文字
     * @param songs // 歌曲列表
     * @return
     */
    private String formatSongs(String title, List<SongVO> songs) {
        if (CollectionUtils.isEmpty(songs)) {
            return title + "：没有找到匹配歌曲。";
        }

        String content = songs.stream()
                .limit(6)
                .map(song -> "%d - %s / %s / %s".formatted(
                        song.getSongId(),
                        safe(song.getSongName()),
                        safe(song.getArtistName()),
                        safe(song.getAlbum())
                ))
                .collect(Collectors.joining("\n"));
        return """
                %s:
                %s
                """.formatted(title, content).trim(); //trim() - 去除首尾空格
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
