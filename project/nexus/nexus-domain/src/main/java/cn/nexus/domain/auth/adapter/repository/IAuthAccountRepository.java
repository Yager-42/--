package cn.nexus.domain.auth.adapter.repository;

import cn.nexus.domain.auth.model.entity.AuthAccountEntity;
import java.util.List;

/**
 * 认证账号仓储。
 */
public interface IAuthAccountRepository {

    /**
     * 判断手机号是否已注册。
     *
     * @param phone 手机号
     * @return 是否存在
     */
    boolean existsByPhone(String phone);

    /**
     * 按手机号获取账号；不存在时由上层决定如何报错。
     *
     * @param phone 手机号
     * @return 账号
     */
    AuthAccountEntity requireByPhone(String phone);

    /**
     * 按用户 ID 获取账号。
     *
     * @param userId 用户 ID
     * @return 账号
     */
    AuthAccountEntity requireByUserId(Long userId);

    /**
     * 批量按用户 ID 获取账号。
     *
     * @param userIds 用户 ID 列表
     * @return 账号列表
     */
    List<AuthAccountEntity> listByUserIds(List<Long> userIds);

    /**
     * 创建认证账号。
     *
     * @param entity 账号实体
     */
    void create(AuthAccountEntity entity);

    /**
     * 更新密码。
     *
     * @param userId 用户 ID
     * @param passwordHash 新密码哈希
     * @param passwordUpdatedAt 更新时间
     */
    void updatePassword(Long userId, String passwordHash, Long passwordUpdatedAt);

    /**
     * 更新最近登录时间。
     *
     * @param userId 用户 ID
     * @param lastLoginAt 最近登录时间
     */
    void touchLastLogin(Long userId, Long lastLoginAt);
}
