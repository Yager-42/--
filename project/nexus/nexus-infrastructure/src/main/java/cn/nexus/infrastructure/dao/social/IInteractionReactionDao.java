package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionReactionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 互动-点赞/态势事实表 DAO。
 *
 * @author codex
 * @since 2026-01-20
 */
@Mapper
public interface IInteractionReactionDao {

    int batchUpsert(@Param("list") List<InteractionReactionPO> list);

    int batchDelete(@Param("targetType") String targetType,
                    @Param("targetId") Long targetId,
                    @Param("reactionType") String reactionType,
                    @Param("userIds") List<Long> userIds);

    Integer selectExists(@Param("targetType") String targetType,
                         @Param("targetId") Long targetId,
                         @Param("reactionType") String reactionType,
                         @Param("userId") Long userId);
}

