package cn.edu.seig.vibemusic.constant;

public class RsdisConstants {

    // 所有歌曲缓存
    public static final String ALL_SONGIDS = "All:SongIds:";
    // 所有歌单缓存
    public static final String ALL_PLAYLISTIDS = "All:PlaylistIds:";
    // 所有歌手缓存
    public static final String ALL_SINGERIDS = "All:SingerIds:";

    // 用户推荐歌曲缓存
    public static final String RECOMMEND_SONGIDS = "Recommend:SongIds:";
    // 用户推荐歌单缓存
    public static final String RECOMMEND_PLAYLISTIDS = "Recommend:PlaylistIds:";

    // 歌曲详情缓存
    public static final String DETAIL_SONGIDS = "Detail:SongIds:";
    // 歌单详情缓存
    public static final String DETAIL_PLAYLISTIDS = "Detail:PlaylistIds:";
    // 歌手详情缓存
    public static final String DETAIL_SINGERIDS = "Detail:SingerIds:";

    // 用户收藏歌曲 ID 列表
    public static final String FAV_SONGIDS = "Fav:SongIds:";
    // 用户收藏歌单 ID 列表
    public static final String FAV_PLAYLIST = "Fav:Playlist:";

    // 评论点赞用户缓存
    public static final String COMMENT_LIKE = "comment:like:";
    // 评论点赞数量缓存
    public static final String COMMENT_LIKE_COUNT = "comment:likeCount:";

    // Spring Cache TTL
    public static final long SPRING_CACHE_TTL_HOURS = 6L;

    // Session / verification TTL
    public static final long LOGIN_TOKEN_TTL_HOURS = 6L;
    public static final long VERIFICATION_CODE_TTL_MINUTES = 5L;

    // Recommendation TTL
    public static final long RECOMMEND_PLAYLIST_TTL_MINUTES = 30L;
    public static final long RECOMMEND_SONG_TTL_MINUTES = 30L;
    public static final int RECOMMEND_SONG_TTL_RANDOM_BOUND_MINUTES = 15;

    // Detail / list cache TTL
    public static final long DETAIL_CACHE_TTL_MINUTES = 60L;
    public static final int DETAIL_CACHE_TTL_RANDOM_BOUND_MINUTES = 30;
    public static final long NULL_CACHE_TTL_MINUTES = 5L;

    // Favorite cache TTL
    public static final long FAVORITE_CACHE_TTL_HOURS = 24L;

    // Comment like cache TTL
    public static final long COMMENT_LIKE_CACHE_TTL_SECONDS = 86400L;
    public static final long COMMENT_LIKE_CACHE_RANDOM_OFFSET_SECONDS = 3600L;

    // Distributed lock TTL
    public static final long LOCK_TTL_SECONDS = 10L;
}
