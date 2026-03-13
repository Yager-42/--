package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友请求结果值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequestResultVO {
    private Long requestId;
    private String status;
}
