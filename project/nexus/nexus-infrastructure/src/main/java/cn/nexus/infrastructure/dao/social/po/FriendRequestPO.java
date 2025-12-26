package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 好友请求持久化对象，对应 friend_request。
 */
@Data
public class FriendRequestPO {
    private Long requestId;
    private Long sourceId;
    private Long targetId;
    private String idempotentKey;
    private Integer status;
    private Long version;
    private Date createTime;
}
