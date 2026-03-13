package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * `user_base` 表访问接口。
 *
 * <p>这里故意只暴露最小 CRUD 能力，方便用户域把“显式查重”“批量补资料”“局部 Patch 更新”收口在一处。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-01-21
 */
@Mapper
public interface IUserBaseDao {
    /**
     * 按用户 ID 查询基础名片。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 用户基础记录，不存在时返回 {@code null}，类型：{@link UserBasePO}
     */
    UserBasePO selectByUserId(@Param("userId") Long userId);

    /**
     * 按用户名查询基础名片。
     *
     * @param username 用户名，类型：{@link String}
     * @return 用户基础记录，不存在时返回 {@code null}，类型：{@link UserBasePO}
     */
    UserBasePO selectByUsername(@Param("username") String username);

    /**
     * 批量按用户 ID 查询基础名片。
     *
     * @param userIds 用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 用户基础记录列表，类型：{@link List}&lt;{@link UserBasePO}&gt;
     */
    List<UserBasePO> selectByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 批量按用户名查询基础名片。
     *
     * @param usernames 用户名列表，类型：{@link List}&lt;{@link String}&gt;
     * @return 用户基础记录列表，类型：{@link List}&lt;{@link UserBasePO}&gt;
     */
    List<UserBasePO> selectByUsernames(@Param("usernames") List<String> usernames);

    /**
     * 插入一条最小用户名片。
     *
     * @param po 用户基础记录，类型：{@link UserBasePO}
     * @return 影响行数，类型：{@code int}
     */
    int insert(UserBasePO po);

    /**
     * Patch 更新基础名片。
     *
     * <p>`nickname/avatarUrl` 传 {@code null} 表示不改，`avatarUrl` 允许传空串清空。</p>
     * <p>注意：MySQL `affectedRows` 可能因为“值相同”返回 `0`，调用方不能直接把它当成 `NOT_FOUND`。</p>
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param nickname 新昵称，为 {@code null} 时表示不改，类型：{@link String}
     * @param avatarUrl 新头像地址，为 {@code null} 时表示不改，类型：{@link String}
     * @return 影响行数，类型：{@code int}
     */
    int updatePatch(@Param("userId") Long userId,
                    @Param("nickname") String nickname,
                    @Param("avatarUrl") String avatarUrl);
}
