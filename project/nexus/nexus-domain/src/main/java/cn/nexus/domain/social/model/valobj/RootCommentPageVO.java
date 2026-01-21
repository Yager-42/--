package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一级评论分页结果。
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
public class RootCommentPageVO {
    private RootCommentViewVO pinned;
    private List<RootCommentViewVO> items;
    private String nextCursor;
}

