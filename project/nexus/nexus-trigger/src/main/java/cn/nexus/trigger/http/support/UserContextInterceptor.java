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
 * 当前用户注入拦截器：只从 Token 解析当前用户并写入 {@link UserContext}。
 *
 * <p>这层的职责很单一：把“当前请求是谁”收口到一个地方。后面的 Controller 只从 {@link UserContext} 取值，
 * 不再自己到处解析 Token。</p>
 *
 * <p>注意：</p>
 * <p>1) 必须在 {@link #afterCompletion(HttpServletRequest, HttpServletResponse, Object, Exception)} 清理
 * {@link ThreadLocal}，否则线程复用会串号。</p>
 * <p>2) 必须放行 {@code OPTIONS} 预检请求，否则浏览器跨域请求会直接失败。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-01-15
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    /**
     * 进入业务前解析当前请求用户。
     *
     * @param request 当前 HTTP 请求，类型：{@link HttpServletRequest}
     * @param response 当前 HTTP 响应，类型：{@link HttpServletResponse}
     * @param handler 当前处理器，类型：{@link Object}
     * @return 是否继续执行后续链路，类型：{@code boolean}
     * @throws Exception 写回错误响应时可能抛出的异常，类型：{@link Exception}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 防御性清理：避免容器线程复用时残留脏数据
        UserContext.clear();

        // CORS 预检请求不带业务 Header，必须放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        Long tokenUserId = resolveUserIdFromToken();
        if (tokenUserId == null) {
            writeIllegalParameter(response);
            return false;
        }
        UserContext.setUserId(tokenUserId);
        return true;
    }

    /**
     * 请求完成后清理线程上下文。
     *
     * @param request 当前 HTTP 请求，类型：{@link HttpServletRequest}
     * @param response 当前 HTTP 响应，类型：{@link HttpServletResponse}
     * @param handler 当前处理器，类型：{@link Object}
     * @param ex 请求期间抛出的异常，允许为空，类型：{@link Exception}
     */
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
}

