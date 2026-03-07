package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionReactionPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 互动-点赞/态势事实表 DAO。
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

    int insertIgnore(@Param("targetType") String targetType,
                     @Param("targetId") Long targetId,
                     @Param("reactionType") String reactionType,
                     @Param("userId") Long userId);

    int deleteOne(@Param("targetType") String targetType,
                  @Param("targetId") Long targetId,
                  @Param("reactionType") String reactionType,
                  @Param("userId") Long userId);

    List<InteractionReactionPO> pageByTarget(@Param("targetType") String targetType,
                                             @Param("targetId") Long targetId,
                                             @Param("reactionType") String reactionType,
                                             @Param("cursorTime") Date cursorTime,
                                             @Param("cursorUserId") Long cursorUserId,
                                             @Param("limit") Integer limit);

    List<Long> batchExists(@Param("targetType") String targetType,
                           @Param("reactionType") String reactionType,
                           @Param("userId") Long userId,
                           @Param("targetIds") List<Long> targetIds);
}
