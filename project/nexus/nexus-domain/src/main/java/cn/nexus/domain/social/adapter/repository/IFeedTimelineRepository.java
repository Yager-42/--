package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;

import java.util.List;

/**
 * Feed 时间线仓储接口：封装 InboxTimeline（Redis ZSET）的读写。
 *
 * @author codex
 * @since 2026-01-12
 */
public interface IFeedTimelineRepository {

    /**
     * 将一条内容写入某个用户的 InboxTimeline。
     *
     * @param userId        用户 ID（收件箱拥有者） {@link Long}
     * @param postId        内容 ID {@link Long}
     * @param publishTimeMs 发布毫秒时间戳（用作 ZSET score） {@link Long}
     */
    void addToInbox(Long userId, Long postId, Long publishTimeMs);

    /**
     * 判断用户 InboxTimeline 是否存在（以 inbox key 是否存在定义“在线”）。
     *
     * @param userId 用户 ID {@link Long}
     * @return true=存在，false=不存在 {@code boolean}
     */
    boolean inboxExists(Long userId);

    /**
     * 原子化重建用户 InboxTimeline：写入临时 key 并通过 RENAME 覆盖正式 inbox。
     *
     * <p>注意：即使 entries 为空，也必须写入“无更多数据”哨兵，避免反复重建。</p>
     *
     * @param userId  用户 ID {@link Long}
     * @param entries inbox 条目列表 {@link List} {@link FeedInboxEntryVO}
     */
    void replaceInbox(Long userId, List<FeedInboxEntryVO> entries);

    /**
     * 分页读取用户 InboxTimeline 的 postId 列表。
     *
     * <p>cursor 协议：cursor=上一页最后一条的 postId（字符串），内部使用 ZREVRANK 定位并翻页。</p>
     *
     * @param userId 用户 ID {@link Long}
     * @param cursor 游标（上一页最后一个 postId），为空表示从最新开始 {@link String}
     * @param limit  单页数量 {@code int}
     * @return 分页结果（postIds + nextCursor） {@link FeedIdPageVO}
     */
    FeedIdPageVO pageInbox(Long userId, String cursor, int limit);

    /**
     * 分页读取用户 InboxTimeline 的索引条目列表（带 publishTimeMs score，用于 Max_ID 多源合并）。
     *
     * <p>Max_ID 语义：publishTimeMs DESC + postId DESC。</p>
     *
     * @param userId       用户 ID {@link Long}
     * @param cursorTimeMs 游标时间（首页传 null 表示从最新开始） {@link Long}
     * @param cursorPostId 游标 postId（首页传 null 表示从最新开始） {@link Long}
     * @param limit        单页数量 {@code int}
     * @return 索引条目列表 {@link List} {@link FeedInboxEntryVO}
     */
    List<FeedInboxEntryVO> pageInboxEntries(Long userId, Long cursorTimeMs, Long cursorPostId, int limit);

    /**
     * 从 InboxTimeline 删除一条索引（幂等）。用于读时修复后的懒清理。
     *
     * @param userId 用户 ID {@link Long}
     * @param postId 内容 ID {@link Long}
     */
    void removeFromInbox(Long userId, Long postId);
}
