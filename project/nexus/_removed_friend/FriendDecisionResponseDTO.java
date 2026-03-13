package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友申请处理结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendDecisionResponseDTO {
    private boolean success;
}
