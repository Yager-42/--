package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 关系领域服务。
 */
public interface IRelationService {

    FollowResultVO follow(Long sourceId, Long targetId);

    FriendRequestResultVO friendRequest(Long sourceId, Long targetId, String verifyMsg, String sourceChannel);

    FriendDecisionResultVO friendDecision(Long requestId, String action);

    OperationResultVO block(Long sourceId, Long targetId);

    RelationGroupVO manageGroup(Long userId, String action, String listName, Long listId, java.util.List<Long> memberIds);
}
