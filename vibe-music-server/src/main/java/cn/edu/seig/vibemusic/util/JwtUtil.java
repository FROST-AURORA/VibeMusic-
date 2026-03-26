package cn.edu.seig.vibemusic.util;

import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtil {

    // 密钥
    private static final String SECRET_KEY = "VIBE_MUSIC"; // 更改为你的密钥
    // 设置 JWT 的过期时间 6 小时
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 6;

    /**
     * 生成 JWT token
     *
     * @param claims 自定义的业务数据
     * @return JWT token
     */
    public static String generateToken(Map<String, Object> claims) {
        return JWT.create()
                .withClaim("claims", claims) // 自定义的业务数据
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // 设置过期时间
                .sign(Algorithm.HMAC256(SECRET_KEY)); // 使用 HMAC256 算法加密
    }

    /**
     * 解析 JWT token
     *
     * @param token JWT token
     * @return 自定义的业务数据
     */
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(Algorithm.HMAC256(SECRET_KEY))
                .build()
                .verify(token)
                .getClaim("claims")
                .asMap();
    }

    /**
     * 从请求中安全地获取用户 ID (弱登录支持)
     * @return userId 或 null（游客状态）
     */
    public static Long getUserIdFromRequest(HttpServletRequest request) {
        String token = request.getHeader("Authorization");

        // 1. 基础拦截：没有 Header 或 格式不对，直接判定为游客
        if (token == null || !token.startsWith("Bearer ")) {
            return null;
        }
        try {
            // 2. 截取 Token 并调用你原有的解析逻辑
            token = token.substring(7);
            Map<String, Object> claims = parseToken(token);

            // 3. 提取用户 ID
            if (claims != null && claims.containsKey(JwtClaimsConstant.USER_ID)) {
                return Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            }
        } catch (Exception e) {
            // 4. 解析失败（过期、篡改等），安静地返回 null，不让业务挂掉
            return null;
        }
        return null;
    }

}
