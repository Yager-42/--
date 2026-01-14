package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;

import java.util.List;

/**
 * 大 V 聚合池仓储接口：把“很多大 V 的 Outbox 读取”降级为“少量 Redis key 读取”。（Redis ZSET）
 *
 * <p>聚合池是兜底优化，不改变核心语义：读侧仍需要按“我关注了哪些大 V”过滤。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface IFeedBigVPoolRepository {

    /**
     * 将一条内容写入大 V 聚合池（按 authorId 分桶）。
     *
     * @param authorId      作者用户 ID {@link Long}
     * @param postId        内容 ID {@link Long}
     * @param publishTimeMs 发布时间毫秒时间戳 {@link Long}
     */
    void addToPool(Long authorId, Long postId, Long publishTimeMs);

    /**
     * 从聚合池删除一条内容索引（幂等）。
     *
     * @param authorId 作者用户 ID {@link Long}
     * @param postId   内容 ID {@link Long}
     */
    void removeFromPool(Long authorId, Long postId);

    /**
     * 分页读取某个 bucket 的聚合池索引条目（Max_ID 语义：publishTimeMs desc + postId desc）。
     *
     * @param bucket        bucket 下标（0..buckets-1） {@code int}
     * @param cursorTimeMs  游标时间（首页传 null 表示从最新开始） {@link Long}
     * @param cursorPostId  游标 postId（首页传 null 表示从最新开始） {@link Long}
     * @param limit         单页数量 {@code int}
     * @return 索引条目列表（按时间倒序） {@link List} {@link FeedInboxEntryVO}
     */
    List<FeedInboxEntryVO> pagePool(int bucket, Long cursorTimeMs, Long cursorPostId, int limit);
}

