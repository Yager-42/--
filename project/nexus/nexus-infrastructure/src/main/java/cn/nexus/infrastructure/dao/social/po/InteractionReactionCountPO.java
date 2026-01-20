package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 互动-态势计数表持久化对象，对应 interaction_reaction_count。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
public class InteractionReactionCountPO {
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Long count;
    private Date updateTime;
}

