package cn.edu.seig.vibemusic.util;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;

import java.util.Map;

/**
 * ThreadLocal 工具类
 */
@SuppressWarnings("all")
public class ThreadLocalUtil {

    // 提供 ThreadLocal 对象，存储 Map<String, Object>
    private static final ThreadLocal<Map<String, Object>> THREAD_LOCAL = new ThreadLocal<>();

    // 根据键获取值
    public static Map<String, Object> get() {
        return THREAD_LOCAL.get();
    }

    // 存储键值对
    public static void set(Map<String, Object> value) {
        THREAD_LOCAL.set(value);
    }

    // 清除 ThreadLocal 防止内存泄漏
    public static void remove() {
        THREAD_LOCAL.remove();
    }

    /**
     * 获取当前用户的 ID
     *
     * @return 用户 ID
     */
    public static Long getUserId() {
        Map<String, Object> map = get();
        if (map == null) {
            throw new IllegalStateException("ThreadLocal 中未存储用户信息");
        }
        Object userIdObj = map.get(JwtClaimsConstant.USER_ID);
        return TypeConversionUtil.toLong(userIdObj);
    }
}
