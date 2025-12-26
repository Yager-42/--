package cn.nexus.api.social.community.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 加入圈子请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupJoinRequestDTO {
    private Long groupId;
    private Long userId;
    private String answers;
    private String inviteToken;
}
