package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Playlist;
import cn.edu.seig.vibemusic.model.vo.PlaylistDetailVO;
import cn.edu.seig.vibemusic.model.vo.PlaylistVO;
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
public interface PlaylistMapper extends BaseMapper<Playlist> {

    // 根据歌单id获取歌单详情
    PlaylistDetailVO getPlaylistDetailById(Long playlistId);

    // 获取用户收藏歌单的风格
    List<String> getFavoritePlaylistStyles(List<Long> favoritePlaylistIds);

    // 获取用户收藏歌单的风格 ID
    List<Long> getFavoritePlaylistStyleIds(@Param("favoritePlaylistIds") List<Long> favoritePlaylistIds);

    // 根据风格推荐歌单 ID（排除已收藏歌单）
    List<Long> getRecommendedPlaylistsIdsByStyles(
            @Param("sortedStyleIds") List<Long> sortedStyleIds,
            @Param("favoritePlaylistIds") List<Long> favoritePlaylistIds,
            @Param("limit") int limit);

    // 随机推荐歌单 ID
    @Select("""
            SELECT p.id
            FROM tb_playlist p
            ORDER BY RAND() 
            LIMIT #{limit}
            """)
    List<Long> getRandomPlaylistIds(int limit);

    // 根据用户收藏的歌单 id 列表获取歌单列表
    IPage<PlaylistVO> getPlaylistsByIds(
            Long userId,
            Page<PlaylistVO> page,
            @Param("playlistIds") List<Long> playlistIds,
            @Param("title") String title,
            @Param("style") String style);
    
    // 查询歌单 id（分页，支持条件过滤）
    @Select("""
        SELECT p.id
        FROM tb_playlist p
        WHERE 
            (#{title} IS NULL OR p.title LIKE CONCAT('%', #{title}, '%'))
            AND (#{style} IS NULL OR p.style = #{style})
        """)
    IPage<Long> queryPlaylistIds(Page<Long> page,
                                 @Param("title") String title,
                                 @Param("style") String style);
    
    // 根据歌单 id 列表获取歌单详情
    List<PlaylistVO> getPlaylistDetailsByIds(List<Long> longs);
}
