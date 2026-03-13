package cn.nexus.domain.social.model.valobj;

import cn.nexus.domain.social.model.entity.ContentPostEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 个人页内容分页结果。
 *
 * <p>cursor 协议：nextCursor="{lastCreateTimeMs}:{lastPostId}"</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPostPageVO {

    /**
     * 内容列表（按 createTime DESC, postId DESC）。
     */
    private List<ContentPostEntity> posts;

    /**
     * 下一页游标："{lastCreateTimeMs}:{lastPostId}"；为空表示没有更多。
     */
    private String nextCursor;
}

