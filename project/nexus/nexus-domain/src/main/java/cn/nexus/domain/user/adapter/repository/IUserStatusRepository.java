package cn.nexus.domain.user.adapter.repository;

/**
 * 用户状态仓储：维护最小账号状态 `ACTIVE / DEACTIVATED`。
 *
 * <p>约定很明确：状态记录不存在时，默认视为 `ACTIVE`，只拦写，不拦读。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
public interface IUserStatusRepository {

    /**
     * 查询用户当前状态；不存在时返回 `ACTIVE`。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 当前状态；不存在时返回 `ACTIVE`，类型：{@link String}
     */
    String getStatus(Long userId);

    /**
     * Upsert 用户状态。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param status 目标状态，只允许 `ACTIVE / DEACTIVATED`，类型：{@link String}
     * @param deactivatedTimeMs 停用时间毫秒值；仅 `DEACTIVATED` 场景有意义，类型：{@link Long}
     */
    void upsertStatus(Long userId, String status, Long deactivatedTimeMs);
}
