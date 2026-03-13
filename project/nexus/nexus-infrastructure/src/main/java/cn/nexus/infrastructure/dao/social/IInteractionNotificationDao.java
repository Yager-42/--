package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionNotificationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface IInteractionNotificationDao {

    List<InteractionNotificationPO> pageByUser(@Param("toUserId") Long toUserId,
                                              @Param("cursorTime") Date cursorTime,
                                              @Param("cursorId") Long cursorId,
                                              @Param("limit") int limit);

    int upsertIncrement(@Param("notificationId") Long notificationId,
                        @Param("toUserId") Long toUserId,
                        @Param("bizType") String bizType,
                        @Param("targetType") String targetType,
                        @Param("targetId") Long targetId,
                        @Param("postId") Long postId,
                        @Param("rootCommentId") Long rootCommentId,
                        @Param("lastActorUserId") Long lastActorUserId,
                        @Param("lastCommentId") Long lastCommentId,
                        @Param("delta") Long delta);

    int markRead(@Param("toUserId") Long toUserId, @Param("notificationId") Long notificationId);

    int markReadAll(@Param("toUserId") Long toUserId);
}

