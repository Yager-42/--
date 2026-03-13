package cn.nexus.infrastructure.adapter.user.repository;

import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.infrastructure.dao.user.IUserStatusDao;
import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户状态仓储实现：user_status 不存在视为 ACTIVE（只拦写，不拦读）。
 */
@Repository
@RequiredArgsConstructor
public class UserStatusRepository implements IUserStatusRepository {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final IUserStatusDao userStatusDao;

    @Override
    public String getStatus(Long userId) {
        if (userId == null) {
            return STATUS_ACTIVE;
        }
        UserStatusPO po = userStatusDao.selectByUserId(userId);
        if (po == null || po.getStatus() == null || po.getStatus().isBlank()) {
            return STATUS_ACTIVE;
        }
        return po.getStatus();
    }

    @Override
    public void upsertStatus(Long userId, String status, Long deactivatedTimeMs) {
        if (userId == null || status == null || status.isBlank()) {
            return;
        }
        UserStatusPO po = new UserStatusPO();
        po.setUserId(userId);
        po.setStatus(status);
        po.setDeactivatedTime(deactivatedTimeMs == null ? null : new Date(deactivatedTimeMs));
        userStatusDao.upsert(po);
    }
}
