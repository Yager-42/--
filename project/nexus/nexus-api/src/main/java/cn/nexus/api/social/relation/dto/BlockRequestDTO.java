package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 屏蔽请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockRequestDTO {
    private Long sourceId;
    private Long targetId;
}
