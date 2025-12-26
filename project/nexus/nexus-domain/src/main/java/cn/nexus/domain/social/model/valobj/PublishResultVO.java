package cn.nexus.domain.social.model.valobj;

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
public class PublishResultVO {
    private Long postId;
    private String status;
}
