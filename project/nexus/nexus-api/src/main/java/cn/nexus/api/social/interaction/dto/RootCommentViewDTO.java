package cn.nexus.api.social.interaction.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一级评论 + 楼内回复预览。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RootCommentViewDTO {
    private CommentViewDTO root;
    private List<CommentViewDTO> repliesPreview;
}

