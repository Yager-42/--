package cn.nexus.domain.social.adapter.repository;

import java.util.List;
import java.util.Map;

/**
 * Feed 作者类别状态机仓储：保存作者类别 NORMAL/BIGV。
 */
public interface IFeedAuthorCategoryRepository {

    Integer getCategory(Long userId);

    Map<Long, Integer> batchGetCategory(List<Long> userIds);

    void setCategory(Long userId, Integer category);
}

