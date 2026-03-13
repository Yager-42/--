package cn.nexus.domain.user.adapter.repository;

/**
 * 用户隐私设置仓储：当前只承载 `needApproval` 这一类最小开关。
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
public interface IUserPrivacyRepository {

    /**
     * 查询用户是否开启“关注需审批”。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 是否需要审批；记录不存在时返回 `false`，类型：{@link Boolean}
     */
    Boolean getNeedApproval(Long userId);

    /**
     * Upsert `needApproval` 开关；不存在时创建默认行。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param needApproval 是否需要审批，类型：{@link Boolean}
     */
    void upsertNeedApproval(Long userId, Boolean needApproval);
}
