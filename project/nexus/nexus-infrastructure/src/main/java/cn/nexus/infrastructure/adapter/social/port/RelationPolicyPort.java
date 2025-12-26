package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.IBlacklistPort;
import cn.nexus.domain.social.model.entity.UserPrivacyEntity;
import cn.nexus.infrastructure.dao.social.IUserPrivacyDao;
import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 关系策略占位实现：可替换为真实黑名单/隐私配置查询。
 */
@Component
@RequiredArgsConstructor
public class RelationPolicyPort implements IRelationPolicyPort {
    private final IUserPrivacyDao userPrivacyDao;
    private final IBlacklistPort blacklistPort;
    @Override
    public boolean isBlocked(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return true;
        }
        return blacklistPort.isBlocked(sourceId, targetId);
    }

    @Override
    public boolean needApproval(Long targetId) {
        if (targetId == null) {
            return false;
        }
        UserPrivacyPO po = userPrivacyDao.selectByUserId(targetId);
        if (po == null) {
            return false;
        }
        return Boolean.TRUE.equals(po.getNeedApproval());
    }
}
