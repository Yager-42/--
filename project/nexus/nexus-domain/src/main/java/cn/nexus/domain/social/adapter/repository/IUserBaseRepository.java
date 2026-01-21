package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.List;

/**
 * 用户基础信息仓储：给读接口补全 nickname/avatar。
 *
 * <p>必须是批量接口，禁止对评论列表做 N+1 单查。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IUserBaseRepository {

    /**
     * 批量查询用户基础信息（不存在的 userId 直接忽略）。
     *
     * @param userIds 用户 ID 列表
     * @return 用户基础信息列表
     */
    List<UserBriefVO> listByUserIds(List<Long> userIds);

    /**
     * 批量按 username 查询用户基础信息（不存在的 username 直接忽略）。
     *
     * <p>用于 @username 提及解析：由后端回表映射到 userId。</p>
     *
     * @param usernames 用户名列表
     * @return 用户基础信息列表
     */
    List<UserBriefVO> listByUsernames(List<String> usernames);
}
