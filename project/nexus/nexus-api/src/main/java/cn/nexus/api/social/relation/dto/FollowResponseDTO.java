package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowResponseDTO {
    private String status;
}
