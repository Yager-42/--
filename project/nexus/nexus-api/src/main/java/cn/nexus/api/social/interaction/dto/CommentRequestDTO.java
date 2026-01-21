package cn.nexus.api.social.interaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发表评论请求。
 *
 * <p>@提及由后端从 {@code content} 解析 {@code @username}，不要让客户端传 userId 列表来“凑 mentions”。</p>
 * <p>为兼容历史客户端：未知字段一律忽略（例如旧版本仍会传 {@code mentions}）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentRequestDTO {
    private Long postId;
    private Long parentId;
    private String content;
}
