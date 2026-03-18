package cn.nexus.domain.auth.adapter.repository;

import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import java.util.List;

/**
 * 认证侧读取 user_base 的最小仓储。
 */
public interface IAuthUserBaseRepository {

    /**
     * 注册时创建公开资料。
     *
     * @param userId 用户 ID
     * @param username 公开唯一标识
     * @param nickname 昵称
     * @param avatarUrl 头像
     */
    void create(Long userId, String username, String nickname, String avatarUrl);

    /**
     * 读取当前用户公开资料。
     *
     * @param userId 用户 ID
     * @return 当前用户信息
     */
    AuthMeVO getMe(Long userId);

    /**
     * 批量读取公开资料。
     *
     * @param userIds 用户 ID 列表
     * @return 用户公开资料列表
     */
    List<AuthMeVO> listByUserIds(List<Long> userIds);
}
