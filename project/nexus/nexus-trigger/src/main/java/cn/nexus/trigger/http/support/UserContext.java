package cn.nexus.trigger.http.support;

import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;

/**
 * 当前请求用户上下文。
 *
 * <p>约定：网关为每个 HTTP 请求注入 Header {@code X-User-Id}，服务端把它当真值使用，不再信任请求体里的
 * {@code userId}。</p>
 *
 * <p>实现方式：通过 {@link ThreadLocal} 把当前请求的 {@code userId} 绑定到当前线程，供 Controller 和
 * Service 直接读取。</p>
 *
 * <p>注意：这不是安全鉴权本身，只是参数来源收口；而且 {@link ThreadLocal} 必须在请求结束时清理，否则线程复用
 * 会串号。</p>
 *
 * @author rr
 * @author codex
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
     * 设置当前请求的 {@code userId}。
     *
     * @param userId 当前请求用户 ID，类型：{@link Long}
     */
    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 获取当前请求的 {@code userId}。
     *
     * @return 当前请求用户 ID，允许为空，类型：{@link Long}
     */
    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 获取当前请求的 {@code userId}。
     *
     * <p>取不到就直接抛错；不要写 fallback，问题应该在联调/测试阶段暴露。</p>
     *
     * @return 当前请求用户 ID，类型：{@link Long}
     */
    public static Long requireUserId() {
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw illegalParameter();
        }
        return userId;
    }

    /**
     * 清理线程上下文。
     *
     * <p>必须在请求结束时调用，防止线程复用导致串号。</p>
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }

    private static AppException illegalParameter() {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
    }
}

