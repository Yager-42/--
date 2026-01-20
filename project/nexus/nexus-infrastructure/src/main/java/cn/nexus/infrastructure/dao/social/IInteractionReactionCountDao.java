package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionReactionCountPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 互动-态势计数表 DAO。
 *
 * @author codex
 * @since 2026-01-20
 */
@Mapper
public interface IInteractionReactionCountDao {

    int insertOrUpdate(InteractionReactionCountPO po);
}

