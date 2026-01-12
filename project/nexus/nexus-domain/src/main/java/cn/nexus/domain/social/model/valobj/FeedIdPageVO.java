package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feed ID 分页结果（用于 timeline 从 Redis 读取 postId 列表）。
 *
 * <p>cursor 协议：nextCursor=本页最后一个 postId（字符串）。</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedIdPageVO {

    /**
     * 本页 postId 列表（按时间倒序）。
     */
    private List<Long> postIds;

    /**
     * 下一页游标：本页最后一个 postId；为空表示没有更多。
     */
    private String nextCursor;
}
