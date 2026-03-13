package cn.nexus.domain.social.adapter.repository;

import java.util.List;

/**
 * 推荐流 session cache 仓储（Redis）：用于保证“同 cursor 重试稳定”。
 *
 * <p>数据结构（按文档 11.11.3 固定）：</p>
 * <ul>
 *     <li>候选列表（顺序）：{@code feed:rec:session:{userId}:{sessionId}}（LIST）</li>
 *     <li>去重集合：{@code feed:rec:seen:{userId}:{sessionId}}（SET）</li>
 *     <li>latest 内部游标：{@code feed:rec:latestCursor:{userId}:{sessionId}}（STRING）</li>
 * </ul>
 *
 * @author codex
 * @since 2026-01-26
 */
public interface IFeedRecommendSessionRepository {

    /**
     * session 是否存在（用于判断 cursor 是否过期/被清理）。
     */
    boolean sessionExists(Long userId, String sessionId);

    /**
     * 候选列表长度。
     */
    long size(Long userId, String sessionId);

    /**
     * 读取候选列表区间（含 start/end）。
     *
     * @param startIndex 起始下标（0-based）
     * @param endIndex   结束下标（0-based，包含）
     */
    List<Long> range(Long userId, String sessionId, long startIndex, long endIndex);

    /**
     * 读取单个候选（0-based）。
     */
    Long get(Long userId, String sessionId, long index);

    /**
     * 追加候选列表：必须在 session 内去重（seen set），返回实际追加条数。
     */
    int appendCandidates(Long userId, String sessionId, List<Long> postIds);

    /**
     * 获取 latest 补齐用的内部游标（格式：\"{publishTimeMs}:{postId}\"）。
     */
    String getLatestCursor(Long userId, String sessionId);

    /**
     * 设置 latest 补齐用的内部游标（格式：\"{publishTimeMs}:{postId}\"）。
     */
    void setLatestCursor(Long userId, String sessionId, String latestCursor);

    /**
     * 删除 session（可选：用于强制失效）。
     */
    void deleteSession(Long userId, String sessionId);
}

