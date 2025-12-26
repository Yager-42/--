package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 粉丝反向表持久化对象，对应 user_follower。
 */
@Data
public class FollowerPO {
    private Long id;
    private Long userId;
    private Long followerId;
    private Date createTime;
}
