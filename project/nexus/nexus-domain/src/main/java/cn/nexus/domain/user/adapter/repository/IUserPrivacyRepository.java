package cn.nexus.domain.user.adapter.repository;

/**
 * 用户隐私设置仓储。
 */
public interface IUserPrivacyRepository {

    /**
     * 查询是否需要关注审批；不存在时返回 false。
     */
    Boolean getNeedApproval(Long userId);

    /**
     * Upsert needApproval（不存在则插入默认行）。
     */
    void upsertNeedApproval(Long userId, Boolean needApproval);
}

