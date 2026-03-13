package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;

import java.util.List;

/**
 * Feed 全站 latest 仓储：用于推荐系统不可用时的兜底候选源（Redis ZSET）。
 *
 * <p>Key: {@code feed:global:latest}，语义：publishTimeMs desc + postId desc（Max_ID）。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
public interface IFeedGlobalLatestRepository {

    /**
     * 写入全站 latest 索引（幂等）。
     *
     * @param postId        内容 ID
     * @param publishTimeMs 发布时间（毫秒）
     */
    void addToLatest(Long postId, Long publishTimeMs);

    /**
     * 分页读取全站 latest 索引（Max_ID 语义）。
     *
     * @param cursorTimeMs 游标时间（首页传 null 表示从最新开始）
     * @param cursorPostId 游标 postId（首页传 null 表示从最新开始）
     * @param limit        单页数量
     * @return 索引条目列表（按时间倒序）
     */
    List<FeedInboxEntryVO> pageLatest(Long cursorTimeMs, Long cursorPostId, int limit);
}

