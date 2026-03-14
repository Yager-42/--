package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.List;

/**
 * 用户基础信息仓储：给读接口批量补全 `nickname / avatarUrl`。
 *
 * <p>这里必须坚持批量接口，不能把 `N + 1` 单查放给调用方。</p>
 *
 * @author rr
 * @author codex
 * @author {$authorName}
 * @since 2026-01-21
 */
public interface IUserBaseRepository {

    /**
     * 批量按 `userId` 查询用户基础信息；不存在的用户直接忽略。
     *
     * @param userIds 用户 ID 列表（元素为 {@link Long}） {@link List}
     * @return 用户基础信息列表（元素为 {@link UserBriefVO}） {@link List}
     */
    List<UserBriefVO> listByUserIds(List<Long> userIds);

    /**
     * 批量按 `username` 查询用户基础信息；不存在的用户名直接忽略。
     *
     * <p>这个接口主要服务 `@username` 提及解析：由后端回表映射成真实 `userId`。</p>
     *
     * @param usernames 用户名列表（元素为 {@link String}） {@link List}
     * @return 用户基础信息列表（元素为 {@link UserBriefVO}） {@link List}
     */
    List<UserBriefVO> listByUsernames(List<String> usernames);
}
