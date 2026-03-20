package cn.nexus.trigger.http.support;

import cn.dev33.satoken.stp.StpInterface;
import cn.nexus.domain.auth.adapter.repository.IAuthRoleRepository;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 角色提供器。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final IAuthRoleRepository authRoleRepository;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        return authRoleRepository.listRoleCodes(userId);
    }

    private Long toUserId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        if (loginId instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(loginId));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
