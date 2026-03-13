package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequestDTO {
    private Long sourceId;
    private Long targetId;
    private String verifyMsg;
    private String sourceChannel;
}
