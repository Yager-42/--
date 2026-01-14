package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;

import java.util.List;

/**
 * Feed Outbox 仓储接口：用于“大 V 拉模式”场景下，作者侧保存可拉取的索引流（Redis ZSET）。
 *
 * <p>Outbox 的本质是“作者发布流索引”，读侧按 Max_ID 游标分页合并。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface IFeedOutboxRepository {

    /**
     * 将一条内容写入作者 Outbox。
     *
     * @param authorId      作者用户 ID {@link Long}
     * @param postId        内容 ID {@link Long}
     * @param publishTimeMs 发布时间毫秒时间戳（用作 ZSET score） {@link Long}
     */
    void addToOutbox(Long authorId, Long postId, Long publishTimeMs);

    /**
     * 从作者 Outbox 删除一条内容索引（幂等）。
     *
     * @param authorId 作者用户 ID {@link Long}
     * @param postId   内容 ID {@link Long}
     */
    void removeFromOutbox(Long authorId, Long postId);

    /**
     * 分页读取作者 Outbox 的索引条目（Max_ID 语义：publishTimeMs desc + postId desc）。
     *
     * @param authorId      作者用户 ID {@link Long}
     * @param cursorTimeMs  游标时间（首页传 null 表示从最新开始） {@link Long}
     * @param cursorPostId  游标 postId（首页传 null 表示从最新开始） {@link Long}
     * @param limit         单页数量 {@code int}
     * @return 索引条目列表（按时间倒序） {@link List} {@link FeedInboxEntryVO}
     */
    List<FeedInboxEntryVO> pageOutbox(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit);
}

