package cn.edu.seig.vibemusic.interceptor;


import cn.edu.seig.vibemusic.config.RolePermissionManager;
import cn.edu.seig.vibemusic.constant.JwtClaimsConstant;
import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.util.JwtUtil;
import cn.edu.seig.vibemusic.util.ThreadLocalUtil;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.util.AntPathMatcher;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RolePermissionManager rolePermissionManager;

    // 1. 引入 Spring 的路径匹配器（支持 /** 通配符）
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS 预检直接放行
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) return true;

        // 2. 使用 getServletPath() 替代 getRequestURI()
        // 这样可以自动去掉 context-path（如 /api），只拿到真实的接口路径
        String requestURI = request.getServletPath();
        String token = request.getHeader("Authorization");

        // 3. 判定是否为公共路径（核心：公共路径无论登录与否都直接放行）
        boolean isPublicPath = isPublic(requestURI);

        // 4. 尝试提取并解析 Token (不管是不是公共路径，只要有有效的 Token 就解析并存入上下文)
        boolean isTokenValid = false;
        String pureToken = (token != null && token.startsWith("Bearer ")) ? token.substring(7).trim() : null;

        if (pureToken != null && !pureToken.isEmpty()) {
            try {
                String redisToken = stringRedisTemplate.opsForValue().get(pureToken);
                if (redisToken != null) {
                    Map<String, Object> claims = JwtUtil.parseToken(pureToken);
                    ThreadLocalUtil.set(claims); // 存入 ThreadLocal 供 Service 使用
                    isTokenValid = true;
                }
            } catch (Exception e) {
                // 解析失败（过期或伪造）
                // 只有在【非公共路径】且【Token无效】时才直接报错
                if (!isPublicPath) {
                    sendErrorResult(response, 401, MessageConstant.SESSION_EXPIRED);
                    return false;
                }
            }
        }

        // 5. 最终决策逻辑（按照优先级排序）

        // A. 如果是公共路径，直接放行 (即便登录了也不检查 RolePermissionManager，避免 403)
        if (isPublicPath) {
            return true;
        }

        // B. 如果不是公共路径，且已登录，检查角色权限
        if (isTokenValid) {
            String role = (String) ThreadLocalUtil.get().get(JwtClaimsConstant.ROLE);
            if (rolePermissionManager.hasPermission(role, requestURI)) {
                return true;
            } else {
                sendErrorResult(response, 403, MessageConstant.NO_PERMISSION);
                return false;
            }
        }

        // C. 既不是公共路径，也没登录，直接拦截
        sendErrorResult(response, 401, MessageConstant.NOT_LOGIN);
        return false;
    }

    /**
     * 精准匹配公共路径
     */
    private boolean isPublic(String uri) {
        // 使用 /** 确保可以匹配该目录下的所有子路径
        List<String> publicPatterns = Arrays.asList(
                "/playlist/**",
                "/artist/**",
                "/song/**",
                "/banner/**"
        );
        // AntPathMatcher 可以识别 /artist/getAllArtists 符合 /artist/**
        return publicPatterns.stream().anyMatch(pattern -> pathMatcher.match(pattern, uri));
    }

    private void sendErrorResult(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.error(msg)));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ThreadLocalUtil.remove();
    }
}