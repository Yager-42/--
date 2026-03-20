package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.IBlacklistPort;
import cn.nexus.domain.social.model.entity.UserPrivacyEntity;
import cn.nexus.infrastructure.dao.social.IUserPrivacyDao;
import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 关系策略端口实现：对外提供拉黑判断和隐私开关读取。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Component
@RequiredArgsConstructor
public class RelationPolicyPort implements IRelationPolicyPort {
    private final IUserPrivacyDao userPrivacyDao;
    private final IBlacklistPort blacklistPort;

    /**
     * 判断目标用户是否屏蔽了来源用户。
     *
     * @param sourceId 来源用户 ID，类型：{@link Long}
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @return 已屏蔽时返回 {@code true}，否则返回 {@code false}，类型：{@code boolean}
     */
    @Override
    public boolean isBlocked(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return true;
        }
        return blacklistPort.isBlocked(sourceId, targetId);
    }

    /**
     * 读取目标用户是否开启“关注需审批”。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @return 开启审批时返回 {@code true}，否则返回 {@code false}，类型：{@code boolean}
     */
    @Override
    public boolean needApproval(Long targetId) {
        if (targetId == null) {
            return false;
        }
        // 配置缺失时直接回退为 false，不在关系域里偷偷补默认记录。
        UserPrivacyPO po = userPrivacyDao.selectByUserId(targetId);
        if (po == null) {
            return false;
        }
        return Boolean.TRUE.equals(po.getNeedApproval());
    }
}
