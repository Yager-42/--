package cn.nexus.infrastructure.adapter.auth.repository;

import cn.nexus.domain.auth.adapter.repository.IAuthRoleRepository;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.auth.IAuthRoleDao;
import cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao;
import cn.nexus.infrastructure.dao.auth.po.AuthRolePO;
import cn.nexus.infrastructure.dao.auth.po.AuthUserRolePO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 认证角色仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class AuthRoleRepository implements IAuthRoleRepository {

    private final IAuthRoleDao authRoleDao;
    private final IAuthUserRoleDao authUserRoleDao;
    private final ISocialIdPort socialIdPort;

    @Override
    public void assignRole(Long userId, String roleCode) {
        Long normalizedUserId = requireUserId(userId);
        AuthRolePO role = authRoleDao.selectByRoleCode(requireRoleCode(roleCode));
        if (role == null || role.getRoleId() == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), "role 不存在");
        }

        AuthUserRolePO po = new AuthUserRolePO();
        po.setId(requireGeneratedId(socialIdPort.nextId()));
        po.setUserId(normalizedUserId);
        po.setRoleId(role.getRoleId());
        authUserRoleDao.insertIgnore(po);
    }

    @Override
    public void removeRole(Long userId, String roleCode) {
        authUserRoleDao.deleteByUserIdAndRoleCode(requireUserId(userId), requireRoleCode(roleCode));
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        List<String> roleCodes = authUserRoleDao.selectRoleCodesByUserId(requireUserId(userId));
        return roleCodes == null ? Collections.emptyList() : roleCodes;
    }

    @Override
    public List<Long> listUserIdsByRoleCode(String roleCode) {
        List<Long> userIds = authUserRoleDao.selectUserIdsByRoleCode(requireRoleCode(roleCode));
        return userIds == null ? Collections.emptyList() : userIds;
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId 不能为空");
        }
        return userId;
    }

    private String requireRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "roleCode 不能为空");
        }
        return roleCode.trim();
    }

    private Long requireGeneratedId(Long value) {
        if (value == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "userRoleId 生成失败");
        }
        return value;
    }
}
