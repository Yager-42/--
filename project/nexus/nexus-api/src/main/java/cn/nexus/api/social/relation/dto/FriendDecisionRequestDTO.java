package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友申请处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendDecisionRequestDTO {
    private Long requestId;
    private String action;
}
