package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;

import java.util.List;

/**
 * Feed AuthorTimeline 仓储接口：作者侧发布流索引（Redis ZSET）。
 *
 * <p>Key：feed:timeline:{authorId}，member=postId，score=publishTimeMs。</p>
 * <p>读侧按 Max-ID 游标分页拉取。</p>
 *
 * @author codex
 * @since 2026-05-04
 */
public interface IFeedAuthorTimelineRepository {

    /**
     * 将一条内容写入作者 Timeline。
     *
     * @param authorId      作者用户 ID {@link Long}
     * @param postId        内容 ID {@link Long}
     * @param publishTimeMs 发布时间毫秒时间戳 {@link Long}
     */
    void addToTimeline(Long authorId, Long postId, Long publishTimeMs);

    /**
     * 从作者 Timeline 删除一条内容索引（幂等）。
     *
     * @param authorId 作者用户 ID {@link Long}
     * @param postId   内容 ID {@link Long}
     */
    void removeFromTimeline(Long authorId, Long postId);

    /**
     * 分页读取作者 Timeline 的索引条目（Max-ID 语义：publishTimeMs desc + postId desc）。
     *
     * @param authorId      作者用户 ID {@link Long}
     * @param cursorTimeMs  游标时间（首页传 null 表示从最新开始） {@link Long}
     * @param cursorPostId  游标 postId（首页传 null 表示从最新开始） {@link Long}
     * @param limit         单页数量 {@code int}
     * @return 索引条目列表（按时间倒序） {@link List} {@link FeedInboxEntryVO}
     */
    List<FeedInboxEntryVO> pageTimeline(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit);

    /**
     * 判断作者 Timeline 是否存在。
     *
     * @param authorId 作者用户 ID {@link Long}
     * @return true 如果 Timeline 存在 {@code boolean}
     */
    boolean timelineExists(Long authorId);
}
