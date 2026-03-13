package cn.nexus.trigger.http.support;

import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;

/**
 * 当前请求用户上下文。
 *
 * <p>约定：网关为每个 HTTP 请求注入 Header {@code X-User-Id}，服务端把它当真值使用（不做签名/鉴权校验）。</p>
 *
 * <p>实现方式：通过 {@link ThreadLocal} 把当前请求的 userId 绑定到当前线程，供 Controller/Service 直接读取。</p>
 *
 * <p>注意：这不是“安全设计”，只是参数来源约束；且 ThreadLocal 必须在请求结束时清理，否则线程复用会串号（致命 bug）。</p>
 *
 * @author Codex
 * @since 2026-01-15
 */
public final class UserContext {

    /**
     * 网关注入的用户 ID Header 名称。
     */
    public static final String HEADER_USER_ID = "X-User-Id";

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 设置当前请求 userId（由 HTTP 拦截器统一写入）。
     *
     * @param userId {@link Long} 当前请求用户 ID
     */
    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 获取当前请求 userId（可能为空）。
     *
     * @return {@link Long} userId
     */
    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 获取当前请求的 userId。
     *
     * <p>取不到就直接抛错；不要写 fallback，问题应该在联调/测试阶段暴露。</p>
     *
     * @return {@link Long} userId
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw illegalParameter();
        }
        return userId;
    }

    /**
     * 清理线程上下文（必须在请求结束时调用，防止线程复用导致串号）。
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }

    private static AppException illegalParameter() {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
    }
}

