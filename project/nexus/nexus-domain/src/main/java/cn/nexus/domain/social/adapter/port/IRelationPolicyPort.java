package cn.nexus.domain.social.adapter.port;

/**
 * 关系策略端口，承载隐私/黑名单等外部策略。
 */
public interface IRelationPolicyPort {

    /**
     * 是否被目标屏蔽。
     */
    boolean isBlocked(Long sourceId, Long targetId);

    /**
     * 是否需要审批（私密账号）。
     */
    boolean needApproval(Long targetId);
}
