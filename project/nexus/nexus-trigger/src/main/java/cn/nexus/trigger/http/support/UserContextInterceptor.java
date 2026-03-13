package cn.nexus.trigger.http.support;

import cn.dev33.satoken.stp.StpUtil;
import cn.nexus.api.response.Response;
import cn.nexus.types.enums.ResponseCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * userId 注入拦截器：从 HTTP Header 解析 userId 并写入 {@link UserContext}。
 *
 * <p>注意：</p>
 * <p>1) 必须在 {@link #afterCompletion(HttpServletRequest, HttpServletResponse, Object, Exception)} 清理 ThreadLocal，否则线程复用会串号。</p>
 * <p>2) 必须放行 OPTIONS（CORS 预检），否则浏览器跨域请求会直接失败。</p>
 *
 * @author Codex
 * @since 2026-01-15
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 防御性清理：避免容器线程复用时残留脏数据
        UserContext.clear();

        // CORS 预检请求不带业务 Header，必须放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long tokenUserId = resolveUserIdFromToken();
        if (tokenUserId != null) {
            UserContext.setUserId(tokenUserId);
            return true;
        }

        // 兼容多种 Header：playbook=userId，历史实现=X-User-Id
        String raw = headerFirstNonBlank(request, "userId", UserContext.HEADER_USER_ID);
        if (raw == null || raw.isBlank()) {
            writeIllegalParameter(response);
            return false;
        }

        try {
            long userId = Long.parseLong(raw.trim());
            UserContext.setUserId(userId);
            return true;
        } catch (NumberFormatException e) {
            writeIllegalParameter(response);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeIllegalParameter(HttpServletResponse response) throws Exception {
        Response<Object> body = Response.success(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo(), null);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private Long resolveUserIdFromToken() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String headerFirstNonBlank(HttpServletRequest request, String... headerNames) {
        if (request == null || headerNames == null) {
            return null;
        }
        for (String name : headerNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String v = request.getHeader(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

