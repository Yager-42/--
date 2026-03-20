package cn.nexus.infrastructure.adapter.auth.repository;

import cn.nexus.domain.auth.adapter.repository.IAuthAccountRepository;
import cn.nexus.domain.auth.model.entity.AuthAccountEntity;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.auth.IAuthAccountDao;
import cn.nexus.infrastructure.dao.auth.po.AuthAccountPO;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 认证账号仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class AuthAccountRepository implements IAuthAccountRepository {

    private final IAuthAccountDao authAccountDao;
    private final ISocialIdPort socialIdPort;

    @Override
    public boolean existsByPhone(String phone) {
        return authAccountDao.selectByPhone(phone) != null;
    }

    @Override
    public AuthAccountEntity requireByPhone(String phone) {
        return toEntity(authAccountDao.selectByPhone(phone));
    }

    @Override
    public AuthAccountEntity requireByUserId(Long userId) {
        return toEntity(authAccountDao.selectByUserId(userId));
    }

    @Override
    public List<AuthAccountEntity> listByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<AuthAccountPO> accounts = authAccountDao.selectByUserIds(userIds);
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream().map(this::toEntity).toList();
    }

    @Override
    public void create(AuthAccountEntity entity) {
        if (entity == null) {
            return;
        }
        AuthAccountPO po = toPO(entity);
        if (po.getAccountId() == null) {
            po.setAccountId(socialIdPort.nextId());
        }
        authAccountDao.insert(po);
        entity.setAccountId(po.getAccountId());
    }

    @Override
    public void updatePassword(Long userId, String passwordHash, Long passwordUpdatedAt) {
        authAccountDao.updatePassword(userId, passwordHash, toDate(passwordUpdatedAt));
    }

    @Override
    public void touchLastLogin(Long userId, Long lastLoginAt) {
        authAccountDao.touchLastLogin(userId, toDate(lastLoginAt));
    }

    private AuthAccountEntity toEntity(AuthAccountPO po) {
        if (po == null) {
            return null;
        }
        return AuthAccountEntity.builder()
                .accountId(po.getAccountId())
                .userId(po.getUserId())
                .phone(po.getPhone())
                .passwordHash(po.getPasswordHash())
                .passwordUpdatedAt(toLong(po.getPasswordUpdatedAt()))
                .lastLoginAt(toLong(po.getLastLoginAt()))
                .createTime(toLong(po.getCreateTime()))
                .updateTime(toLong(po.getUpdateTime()))
                .build();
    }

    private AuthAccountPO toPO(AuthAccountEntity entity) {
        AuthAccountPO po = new AuthAccountPO();
        po.setAccountId(entity.getAccountId());
        po.setUserId(entity.getUserId());
        po.setPhone(entity.getPhone());
        po.setPasswordHash(entity.getPasswordHash());
        po.setPasswordUpdatedAt(toDate(entity.getPasswordUpdatedAt()));
        po.setLastLoginAt(toDate(entity.getLastLoginAt()));
        po.setCreateTime(toDate(entity.getCreateTime()));
        po.setUpdateTime(toDate(entity.getUpdateTime()));
        return po;
    }

    private Date toDate(Long millis) {
        return millis == null ? null : new Date(millis);
    }

    private Long toLong(Date date) {
        return date == null ? null : date.getTime();
    }
}
