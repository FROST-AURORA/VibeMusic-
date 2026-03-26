package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.UserFavorite;
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
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    // 查询用户收藏的所有歌曲ID
    @Select("SELECT song_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 0 ORDER BY create_time DESC")
    List<Long> getUserFavoriteSongIds(@Param("userId") Long userId);

    // 查询用户收藏的所有歌单ID
    @Select("SELECT playlist_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 1 ORDER BY create_time DESC")
    List<Long> getUserFavoritePlaylistIds(@Param("userId") Long userId);

    // 查询用户收藏的所有歌曲ID
    @Select("SELECT song_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 0")
    List<Long> getFavoriteSongIdsByUserId(@Param("userId") Long userId);

    // 查询用户收藏的所有歌单ID
    @Select("SELECT playlist_id FROM tb_user_favorite WHERE user_id = #{userId} AND type = 1")
    List<Long> getFavoritePlaylistIdsByUserId(@Param("userId") Long userId);

    // 根据 style 查询对应的 id
    List<Long> getFavoriteIdsByStyle(List<String> favoriteStyles);

    /**
     * 查询用户收藏的歌曲 ID 列表（支持分页和条件过滤）
     *
     * @param page       分页对象
     * @param userId     用户 ID
     * @param songName   歌曲名（模糊查询）
     * @param artistName 歌手名（模糊查询）
     * @param album      专辑名（模糊查询）
     * @return 分页后的歌曲 ID 列表
     */
    IPage<Long> queryFavoriteSongIds(Page<Long> page,
                                     @Param("userId") Long userId,
                                     @Param("songName") String songName,
                                     @Param("artistName") String artistName,
                                     @Param("album") String album);

    /**
     * 查询用户收藏的歌单 ID 列表（支持分页和条件过滤）
     *
     * @param page       分页对象
     * @param userId     用户 ID
     * @param title      歌单标题（模糊查询）
     * @param style      歌单风格（模糊查询）
     * @return 分页后的歌单 ID 列表
     */
    IPage<Long> queryFavoritePlaylistIds(Page<Long> page,
                                         @Param("userId") Long userId,
                                         @Param("title") String title,
                                         @Param("style") String style);

}
