package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 互动-点赞/态势事实表持久化对象，对应 interaction_reaction。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
public class InteractionReactionPO {
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Long userId;
    private Date createTime;
    private Date updateTime;
}

