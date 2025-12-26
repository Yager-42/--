package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友审批结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendDecisionResultVO {
    private boolean success;
}
