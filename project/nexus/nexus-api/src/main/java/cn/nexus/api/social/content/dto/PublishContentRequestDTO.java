package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布内容请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishContentRequestDTO {
    /** 复用已有内容的postId，空则创建新内容 */
    private Long postId;
    private Long userId;
    private String text;
    private String mediaInfo;
    private String location;
    private String visibility;
}
