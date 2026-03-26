package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.Artist;
import cn.edu.seig.vibemusic.model.vo.ArtistDetailVO;
import cn.edu.seig.vibemusic.model.vo.ArtistVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
public interface ArtistMapper extends BaseMapper<Artist> {

    // 根据id查询歌手详情
    ArtistDetailVO getArtistDetailById(Long artistId);

    // 根据多个id查询歌手信息
    List<ArtistVO> getArtistsByIds(@Param("ids") List<Long> ids);

    // 根据歌手名、性别、地区查询歌手id
    IPage<Long> queryArtistIds(Page<Long> page,
                               @Param("artistName") String artistName,
                               @Param("gender") Integer gender,
                               @Param("area") String area);
}