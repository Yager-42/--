package cn.nexus.domain.social.adapter.repository;

/**
 * 搜索历史仓储：Redis LIST（体验数据）。
 */
public interface ISearchHistoryRepository {

    /**
     * 记录搜索关键词（幂等去重由实现负责）。
     */
    void record(Long userId, String keyword);

    /**
     * 清空用户搜索历史。
     */
    void clear(Long userId);
}

