package cn.edu.seig.vibemusic.ai.model;

import lombok.Data;

import java.util.List;

@Data
public class MusicSearchCriteria {

    /**
     * search_song / recommend_song
     */
    private String intent;

    /**
     * 歌曲名
     */
    private String songName;

    /**
     * 歌手名
     */
    private String artistName;

    /**
     * 专辑名
     */
    private String album;

    /**
     * 从自然语言中抽出的风格词。
     */
    private List<String> styleNames;

    /**
     * 情绪词，例如：轻松、伤感、安静。
     * 当前版本先保留，后续可继续映射推荐策略。
     */
    private String mood;

    /**
     * 场景词，例如：学习、跑步、通勤、夜晚。
     */
    private String scene;

    /**
     * 语言，例如：中文、英文。
     */
    private String language;

    /**
     * 分页页码，从 1 开始。
     */
    private Integer pageNum;

    /**
     * 分页大小，默认 10。
     */
    private Integer pageSize;

    /**
     * 最多返回多少个结果。
     */
    private Integer limit;

    /**
     * 是否优先返回个性化结果。
     */
    private Boolean preferPersonalized;
}
