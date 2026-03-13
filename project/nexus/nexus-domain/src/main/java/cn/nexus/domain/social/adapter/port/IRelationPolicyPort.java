package cn.nexus.domain.social.adapter.port;

/**
 * 关系策略端口：承载拉黑和隐私审批这类跨域策略判断。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
public interface IRelationPolicyPort {

    /**
     * 判断 `sourceId` 是否被 `targetId` 屏蔽。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @return 是否被屏蔽，类型：{@code boolean}
     */
    boolean isBlocked(Long sourceId, Long targetId);

    /**
     * 判断目标用户是否开启了“关注需审批”。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @return 是否需要审批，类型：{@code boolean}
     */
    boolean needApproval(Long targetId);
}
