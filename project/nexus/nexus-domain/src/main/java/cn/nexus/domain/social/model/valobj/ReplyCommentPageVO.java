package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 楼内回复分页结果（按时间正序）。
 *
 * <p>cursor 协议：nextCursor="{lastCreateTimeMs}:{lastCommentId}"</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplyCommentPageVO {
    private List<CommentViewVO> items;
    private String nextCursor;
}

