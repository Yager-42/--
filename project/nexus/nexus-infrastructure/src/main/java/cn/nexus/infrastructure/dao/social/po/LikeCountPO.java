package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 点赞计数持久化对象，对应 like_counts。
 */
@Data
public class LikeCountPO {

    private String targetType;
    private Long targetId;
    private Long likeCount;
    private Date updateTime;
}

