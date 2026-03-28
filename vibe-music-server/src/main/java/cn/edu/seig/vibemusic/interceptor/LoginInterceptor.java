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

import java.io.IOException;
import java.util.Map;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RolePermissionManager rolePermissionManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            return true;
        }

        String requestURI = request.getServletPath();
        String token = request.getHeader("Authorization");
        boolean isTokenValid = false;
        String pureToken = (token != null && token.startsWith("Bearer ")) ? token.substring(7).trim() : null;

        if (pureToken != null && !pureToken.isEmpty()) {
            try {
                String redisToken = stringRedisTemplate.opsForValue().get(pureToken);
                if (redisToken != null) {
                    Map<String, Object> claims = JwtUtil.parseToken(pureToken);
                    ThreadLocalUtil.set(claims);
                    isTokenValid = true;
                }
            } catch (Exception e) {
                //如果解析失败，则返回错误信息
                sendErrorResult(response, 401, MessageConstant.SESSION_EXPIRED);
                return false;
            }
        }

        if (isTokenValid) {
            String role = (String) ThreadLocalUtil.get().get(JwtClaimsConstant.ROLE);
            if (rolePermissionManager.hasPermission(role, requestURI)) {
                return true;
            }
            //如果没有权限，则返回错误信息
            sendErrorResult(response, 403, MessageConstant.NO_PERMISSION);
            return false;
        }
        //如果没有登录，则返回错误信息
        sendErrorResult(response, 401, MessageConstant.NOT_LOGIN);
        return false;
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
