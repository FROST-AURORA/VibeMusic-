package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Song;
import cn.edu.seig.vibemusic.model.vo.SongAdminVO;
import cn.edu.seig.vibemusic.model.vo.SongDetailVO;
import cn.edu.seig.vibemusic.model.vo.SongVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface SongMapper extends BaseMapper<Song> {


    // 查询歌曲id
    @Select("""
        SELECT s.id
        FROM tb_song s
        LEFT JOIN tb_artist a ON s.artist_id = a.id
        WHERE 
            (#{songName} IS NULL OR s.name LIKE CONCAT('%', #{songName}, '%'))
            AND (#{artistName} IS NULL OR a.name LIKE CONCAT('%', #{artistName}, '%'))
            AND (#{album} IS NULL OR s.album LIKE CONCAT('%', #{album}, '%'))
        ORDER BY s.release_time DESC
        """)
    IPage<Long> querySongIds(Page<Long> page,
                             @Param("songName") String songName,
                             @Param("artistName") String artistName,
                             @Param("album") String album);

    // 根据 id 批量获取歌曲列表（支持批量查询）
    List<SongVO> getSongsByIdS(@Param("ids") List<Long> ids);

    // admin直接获取歌曲列表
    @Select("""
                SELECT 
                    s.id AS songId, 
                    s.name AS songName, 
                    s.artist_id AS artistId, 
                    s.album, 
                    s.lyric, 
                    s.duration, 
                    s.style, 
                    s.cover_url AS coverUrl, 
                    s.audio_url AS audioUrl, 
                    s.release_time AS releaseTime, 
                    a.name AS artistName
                FROM tb_song s
                LEFT JOIN tb_artist a ON s.artist_id = a.id
                WHERE 
                    (#{artistId} IS NULL OR s.artist_id = #{artistId})
                    AND(#{songName} IS NULL OR s.name LIKE CONCAT('%', #{songName}, '%'))
                    AND (#{album} IS NULL OR s.album LIKE CONCAT('%', #{album}, '%'))
                ORDER BY s.release_time DESC
            """)
    IPage<SongAdminVO> getSongsWithArtistName(Page<SongAdminVO> page,
                                              @Param("artistId") Long artistId,
                                              @Param("songName") String songName,
                                              @Param("album") String album);

    // 批量获取随机歌曲 ID（仅查 ID，不查完整信息）
    @Select("SELECT id FROM tb_song ORDER BY RAND() LIMIT #{limit}")
    List<Long> getRandomSongIds(@Param("limit") int limit);

    // 根据id获取歌曲详情
    SongDetailVO getSongDetailById(Long songId);

    // 根据用户收藏的歌曲 id 列表获取歌曲风格 ID 列表
    List<Long> getFavoriteSongStyles(@Param("favoriteSongIds") List<Long> favoriteSongIds);

    // 根据风格 ID 列表和用户已收藏 ID 列表获取推荐歌曲 ID（仅返回 ID）
    List<Long> getRecommendedSongIdsByStyles(
        @Param("sortedStyleIds") List<Long> sortedStyleIds,
        @Param("favoriteSongIds") List<Long> favoriteSongIds,
        @Param("limit") int limit
    );

}
