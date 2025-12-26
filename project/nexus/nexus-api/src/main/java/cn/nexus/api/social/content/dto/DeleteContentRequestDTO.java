package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 删除内容请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteContentRequestDTO {
    private Long userId;
    private Long postId;
}
