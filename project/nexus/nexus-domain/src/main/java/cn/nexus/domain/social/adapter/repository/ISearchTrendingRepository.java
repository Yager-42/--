package cn.nexus.domain.social.adapter.repository;

import java.util.List;

/**
 * 热搜/联想仓储：Redis ZSET（体验数据）。
 */
public interface ISearchTrendingRepository {

    /**
     * 日热搜计数 +1（category 目前固定 POST）。
     */
    void incr(String category, String keyword);

    /**
     * 获取近窗口聚合热搜 topN（按 score desc）。
     */
    List<String> top(String category, int limit);

    /**
     * 获取近窗口聚合热搜 topK 后按前缀过滤，返回最多 limit 个。
     */
    List<String> topAndFilterPrefix(String category, String prefix, int scanTopK, int limit);
}

