package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionReactionCountDeltaInboxPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计数增量幂等收件箱 DAO：interaction_reaction_count_delta_inbox。
 *
 * @author codex
 * @since 2026-03-04
 */
@Mapper
public interface IInteractionReactionCountDeltaInboxDao {

    int insertIgnore(InteractionReactionCountDeltaInboxPO po);
}
