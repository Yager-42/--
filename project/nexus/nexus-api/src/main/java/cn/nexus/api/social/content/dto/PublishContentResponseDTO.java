package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishContentResponseDTO {
    private Long postId;
    private Long attemptId;
    private Integer versionNum;
    private String status;
}
