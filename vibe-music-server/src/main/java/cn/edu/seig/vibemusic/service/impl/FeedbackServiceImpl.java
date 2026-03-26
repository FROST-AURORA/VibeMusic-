package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.mapper.FeedbackMapper;
import cn.edu.seig.vibemusic.model.dto.FeedbackDTO;
import cn.edu.seig.vibemusic.model.entity.Feedback;
import cn.edu.seig.vibemusic.result.PageResult;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.IFeedbackService;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
@Service
@CacheConfig(cacheNames = "feedbackCache")
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements IFeedbackService {

    @Autowired
    private FeedbackMapper feedbackMapper;

    /**
     * 分页查询反馈信息
     *
     * @param feedbackDTO 反馈信息查询条件
     * @return 反馈信息分页结果
     */
    @Override
    @Cacheable(key = "#feedbackDTO.pageNum + '-' + #feedbackDTO.pageSize + '-' + #feedbackDTO.keyword + '-admin'")
    public Result<PageResult<Feedback>> getAllFeedbacks(FeedbackDTO feedbackDTO) {
        // 分页查询
        Page<Feedback> page = new Page<>(feedbackDTO.getPageNum(), feedbackDTO.getPageSize());
        QueryWrapper<Feedback> queryWrapper = new QueryWrapper<>();
        if (feedbackDTO.getKeyword() != null) {
            queryWrapper.like("feedback", feedbackDTO.getKeyword());
        }
        // 倒序排序
        queryWrapper.orderByDesc("create_time");

        IPage<Feedback> feedbackPage = feedbackMapper.selectPage(page, queryWrapper);
        if (feedbackPage.getRecords().size() == 0) {
            return Result.success(MessageConstant.DATA_NOT_FOUND, new PageResult<>(0L, null));
        }

        return Result.success(new PageResult<>(feedbackPage.getTotal(), feedbackPage.getRecords()));
    }

    /**
     * 删除反馈信息
     *
     * @param feedbackId 反馈信息id
     * @return 反馈信息删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result deleteFeedback(Long feedbackId) {
        if (feedbackMapper.deleteById(feedbackId) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 批量删除反馈信息
     *
     * @param feedbackIds 反馈信息id列表
     * @return 反馈信息批量删除结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result deleteFeedbacks(List<Long> feedbackIds) {
        if (feedbackMapper.deleteByIds(feedbackIds) == 0) {
            return Result.error(MessageConstant.DELETE + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.DELETE + MessageConstant.SUCCESS);
    }

    /**
     * 添加反馈信息
     *
     * @param content 反馈信息内容
     * @return 反馈信息添加结果
     */
    @Override
    @Transactional
    @CacheEvict(cacheNames = "feedbackCache", allEntries = true)
    public Result addFeedback(String content) {
        //从 ThreadLocal 中获取用户信息
        Long userId = ThreadLocalUtil.getUserId();
    
        Feedback feedback = new Feedback();
        feedback.setUserId(userId).setFeedback(content).setCreateTime(LocalDateTime.now());

        if (feedbackMapper.insert(feedback) == 0) {
            return Result.error(MessageConstant.ADD + MessageConstant.FAILED);
        }
        return Result.success(MessageConstant.ADD + MessageConstant.SUCCESS);
    }

}
