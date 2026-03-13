package cn.nexus.domain.user.adapter.repository;

import cn.nexus.domain.user.model.valobj.UserProfileVO;

/**
 * 用户 Profile 仓储：user_base 的读写抽象（username 不可变，仅用于一致性校验）。
 */
public interface IUserProfileRepository {

    /**
     * 查询用户 Profile；不存在返回 null。
     */
    UserProfileVO get(Long userId);

    /**
     * Patch 更新 profile：nickname/avatarUrl 传 null 表示不改。
     *
     * @return true=用户存在（已更新或值相同）；false=用户不存在
     */
    boolean updatePatch(Long userId, String nickname, String avatarUrl);
}
