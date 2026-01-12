package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedIdPageVO;

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
     * @param userId        用户 ID（收件箱拥有者）
     * @param postId        内容 ID
     * @param publishTimeMs 发布毫秒时间戳（用作 ZSET score）
     */
    void addToInbox(Long userId, Long postId, Long publishTimeMs);

    /**
     * 分页读取用户 InboxTimeline 的 postId 列表。
     *
     * <p>cursor 协议：cursor=上一页最后一条的 postId（字符串），内部使用 ZREVRANK 定位并翻页。</p>
     *
     * @param userId 用户 ID
     * @param cursor 游标（上一页最后一个 postId），为空表示从最新开始
     * @param limit  单页数量
     * @return 分页结果（postIds + nextCursor）
     */
    FeedIdPageVO pageInbox(Long userId, String cursor, int limit);
}
