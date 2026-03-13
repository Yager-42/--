package cn.nexus.domain.user.adapter.repository;

import cn.nexus.domain.user.model.valobj.UserProfileVO;

/**
 * 用户 Profile 仓储：抽象 `user_base` 的读取和 Patch 更新。
 *
 * <p>`username` 是不可变字段，只能拿来做一致性校验，不能在这里被改掉。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
public interface IUserProfileRepository {

    /**
     * 查询用户 Profile；不存在返回 `null`。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 用户 Profile；不存在时返回 `null`，类型：{@link UserProfileVO}
     */
    UserProfileVO get(Long userId);

    /**
     * Patch 更新用户 Profile。
     *
     * <p>`nickname` 和 `avatarUrl` 传 `null` 表示不改；`avatarUrl` 允许传空串做清空。</p>
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param nickname 新昵称；`null` 表示不改，类型：{@link String}
     * @param avatarUrl 新头像 URL；`null` 表示不改，类型：{@link String}
     * @return `true` 表示用户存在，`false` 表示用户不存在，类型：{@code boolean}
     */
    boolean updatePatch(Long userId, String nickname, String avatarUrl);
}
