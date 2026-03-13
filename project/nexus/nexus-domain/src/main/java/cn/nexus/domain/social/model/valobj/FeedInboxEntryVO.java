package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Feed Inbox 条目值对象：用于离线重建时批量写入 InboxTimeline。
 *
 * <p>member=postId；score=publishTimeMs。</p>
 *
 * @author codex
 * @since 2026-01-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedInboxEntryVO {

    /**
     * 内容 ID（ZSET member）。 {@link Long}
     */
    private Long postId;

    /**
     * 发布时间毫秒时间戳（ZSET score）。 {@link Long}
     */
    private Long publishTimeMs;
}
