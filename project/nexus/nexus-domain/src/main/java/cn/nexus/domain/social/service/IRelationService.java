package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 关系领域服务。
 */
public interface IRelationService {

    FollowResultVO follow(Long sourceId, Long targetId);

    /**
     * 取消关注。
     *
     * @param sourceId 发起方用户 ID {@link Long}
     * @param targetId 目标用户 ID {@link Long}
     * @return 结果 {@link FollowResultVO}
     */
    FollowResultVO unfollow(Long sourceId, Long targetId);

    FriendRequestResultVO friendRequest(Long sourceId, Long targetId, String verifyMsg, String sourceChannel);

    FriendDecisionResultVO friendDecision(java.util.List<Long> requestIds, String action);

    OperationResultVO block(Long sourceId, Long targetId);

    RelationGroupVO manageGroup(Long userId,
                                String action,
                                String listName,
                                Long listId,
                                java.util.List<Long> memberIds,
                                Long sourceListId,
                                Long targetListId,
                                java.util.List<Long> addMemberIds,
                                java.util.List<Long> removeMemberIds,
                                String idempotentToken);
}
