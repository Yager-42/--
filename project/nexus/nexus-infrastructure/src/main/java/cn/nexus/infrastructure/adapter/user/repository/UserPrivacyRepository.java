package cn.nexus.infrastructure.adapter.user.repository;

import cn.nexus.domain.user.adapter.repository.IUserPrivacyRepository;
import cn.nexus.infrastructure.dao.social.IUserPrivacyDao;
import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户隐私设置仓储实现：复用 user_privacy_setting。
 */
@Repository
@RequiredArgsConstructor
public class UserPrivacyRepository implements IUserPrivacyRepository {

    private final IUserPrivacyDao userPrivacyDao;

    @Override
    public Boolean getNeedApproval(Long userId) {
        if (userId == null) {
            return false;
        }
        UserPrivacyPO po = userPrivacyDao.selectByUserId(userId);
        if (po == null || po.getNeedApproval() == null) {
            return false;
        }
        return po.getNeedApproval();
    }

    @Override
    public void upsertNeedApproval(Long userId, Boolean needApproval) {
        if (userId == null || needApproval == null) {
            return;
        }
        userPrivacyDao.upsertNeedApproval(userId, needApproval);
    }
}

