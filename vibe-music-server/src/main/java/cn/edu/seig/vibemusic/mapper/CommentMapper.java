package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Comment;
import cn.edu.seig.vibemusic.model.vo.CommentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 根据歌曲 ID 查询评论列表（按点赞数降序）
     *
     * @param songId 歌曲 ID
     * @return 评论列表
     */
    List<CommentVO> getCommentListBySongId(@Param("songId") Long songId);

    /**
     * 根据歌单 ID 查询评论列表（按点赞数降序）
     *
     * @param playlistId 歌单 ID
     * @return 评论列表
     */
    List<CommentVO> getCommentListByPlaylistId(@Param("playlistId") Long playlistId);

}
