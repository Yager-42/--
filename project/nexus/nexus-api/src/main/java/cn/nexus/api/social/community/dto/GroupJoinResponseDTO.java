package cn.nexus.api.social.community.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 加入圈子结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupJoinResponseDTO {
    private String status;
}
