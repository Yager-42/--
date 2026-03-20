package cn.nexus.domain.auth.adapter.repository;

import java.util.List;

/**
 * 认证角色仓储。
 */
public interface IAuthRoleRepository {

    /**
     * 给用户赋角色。
     *
     * @param userId 用户 ID
     * @param roleCode 角色编码
     */
    void assignRole(Long userId, String roleCode);

    /**
     * 撤销用户角色。
     *
     * @param userId 用户 ID
     * @param roleCode 角色编码
     */
    void removeRole(Long userId, String roleCode);

    /**
     * 查询用户角色。
     *
     * @param userId 用户 ID
     * @return 角色编码列表
     */
    List<String> listRoleCodes(Long userId);

    /**
     * 按角色查询用户 ID 列表。
     *
     * @param roleCode 角色编码
     * @return 用户 ID 列表
     */
    List<Long> listUserIdsByRoleCode(String roleCode);
}
