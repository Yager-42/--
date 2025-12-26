package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.port.IRelationGroupLockPort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.FriendRequestEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.entity.RelationGroupEntity;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 关系领域服务实现，使用占位逻辑保证接口可用。
 */
@Service
@RequiredArgsConstructor
public class RelationService implements IRelationService {

    private final ISocialIdPort socialIdPort;
    private final IRelationRepository relationRepository;
    private final IRelationEventPort relationEventPort;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRelationAdjacencyCachePort adjacencyCachePort;
    private final IRelationCachePort relationCachePort;
    private final IRelationGroupLockPort relationGroupLockPort;

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_FRIEND = 2;
    private static final int RELATION_BLOCK = 3;

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_PENDING = 2;
    private static final int STATUS_REJECTED = 3;

    private static final String ACTION_MOVE = "MOVE";
    private static final String ACTION_MERGE = "MERGE";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO follow(Long sourceId, Long targetId) {
        // 参数合法校验
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }
        // 屏蔽检查：策略 + 存量屏蔽边
        if (relationPolicyPort.isBlocked(sourceId, targetId) || blockedPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("BLOCKED").build();
        }
        // 触达关注上限
        if (reachFollowLimit(sourceId)) {
            return FollowResultVO.builder().status("LIMIT_REACHED").build();
        }

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (existFollow != null && existFollow.getStatus() != null) {
            return FollowResultVO.builder().status(toStatus(existFollow.getStatus())).build();
        }
        // 好友存在视为已关注
        RelationEntity friendEdge = relationRepository.findRelation(sourceId, targetId, RELATION_FRIEND);
        if (friendEdge != null && friendEdge.getStatus() != null && friendEdge.getStatus() == STATUS_ACTIVE) {
            return FollowResultVO.builder().status("ACTIVE").build();
        }

        boolean needApprove = relationPolicyPort.needApproval(targetId);
        RelationEntity saved = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_FOLLOW)
                .status(needApprove ? STATUS_PENDING : STATUS_ACTIVE)
                .build();
        relationRepository.saveRelation(saved);
        relationRepository.saveFollower(socialIdPort.nextId(), targetId, sourceId);
        adjacencyCachePort.addFollow(sourceId, targetId);
        relationEventPort.onFollow(sourceId, targetId, toStatus(saved.getStatus()));
        return FollowResultVO.builder().status(toStatus(saved.getStatus())).build();
    }

    @Override
    public FriendRequestResultVO friendRequest(Long sourceId, Long targetId, String verifyMsg, String sourceChannel) {
        // 参数/屏蔽/好友/重复申请校验
        if (invalidPair(sourceId, targetId)) {
            return FriendRequestResultVO.builder().status("INVALID").build();
        }
        if (blockedPair(sourceId, targetId)) {
            return FriendRequestResultVO.builder()
                    .requestId(deterministicEdgeId(sourceId, targetId))
                    .status("BLOCKED")
                    .build();
        }
        RelationEntity friendEdge = relationRepository.findRelation(sourceId, targetId, RELATION_FRIEND);
        RelationEntity reverseFriend = relationRepository.findRelation(targetId, sourceId, RELATION_FRIEND);
        if (friendEdge != null || reverseFriend != null) {
            return FriendRequestResultVO.builder()
                    .requestId(friendEdge != null ? friendEdge.getId() : deterministicEdgeId(sourceId, targetId))
                    .status("ACCEPTED")
                    .build();
        }
        FriendRequestEntity pending = relationRepository.findPendingFriendRequest(sourceId, targetId);
        if (pending != null) {
            return FriendRequestResultVO.builder()
                    .requestId(pending.getRequestId())
                    .status(toStatus(pending.getStatus()))
                    .build();
        }

        FriendRequestEntity entity = FriendRequestEntity.builder()
                .requestId(socialIdPort.nextId())
                .sourceId(sourceId)
                .targetId(targetId)
                .status(STATUS_PENDING)
                .build();
        relationRepository.saveFriendRequest(entity);
        return FriendRequestResultVO.builder()
                .requestId(entity.getRequestId())
                .status(toStatus(entity.getStatus()))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendDecisionResultVO friendDecision(Long requestId, String action) {
        FriendRequestEntity request = requestId == null ? null : relationRepository.findFriendRequest(requestId);
        if (request == null || !Objects.equals(request.getStatus(), STATUS_PENDING)) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        boolean accept = "ACCEPT".equalsIgnoreCase(action);
        boolean updated = relationRepository.updateFriendRequestStatus(requestId, accept ? STATUS_ACTIVE : STATUS_REJECTED);
        if (!updated) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        FriendRequestEntity refreshed = relationRepository.findFriendRequest(requestId);
        if (refreshed == null || !Objects.equals(refreshed.getStatus(), accept ? STATUS_ACTIVE : STATUS_REJECTED)) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        if (!accept) {
            return FriendDecisionResultVO.builder().success(true).build();
        }
        // 写入双向好友边
        RelationEntity forward = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(request.getSourceId())
                .targetId(request.getTargetId())
                .relationType(RELATION_FRIEND)
                .status(STATUS_ACTIVE)
                .build();
        RelationEntity backward = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(request.getTargetId())
                .targetId(request.getSourceId())
                .relationType(RELATION_FRIEND)
                .status(STATUS_ACTIVE)
                .build();
        relationRepository.saveRelation(forward);
        relationRepository.saveRelation(backward);
        relationRepository.saveFollower(socialIdPort.nextId(), request.getTargetId(), request.getSourceId());
        relationRepository.saveFollower(socialIdPort.nextId(), request.getSourceId(), request.getTargetId());
        adjacencyCachePort.addFollow(request.getSourceId(), request.getTargetId());
        adjacencyCachePort.addFollow(request.getTargetId(), request.getSourceId());
        relationEventPort.onFriendEstablished(request.getSourceId(), request.getTargetId());
        return FriendDecisionResultVO.builder().success(true).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO block(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return OperationResultVO.builder()
                    .success(false)
                    .status("INVALID")
                    .message("参数错误，未执行屏蔽")
                    .build();
        }
        // 保存屏蔽边
        RelationEntity block = RelationEntity.builder()
                .id(socialIdPort.nextId())
                .sourceId(sourceId)
                .targetId(targetId)
                .relationType(RELATION_BLOCK)
                .status(STATUS_ACTIVE)
                .build();
        relationRepository.saveRelation(block);
        // 清理关注/好友和待处理申请
        relationRepository.deleteRelation(sourceId, targetId, RELATION_FOLLOW);
        relationRepository.deleteRelation(targetId, sourceId, RELATION_FOLLOW);
        relationRepository.deleteRelation(sourceId, targetId, RELATION_FRIEND);
        relationRepository.deleteRelation(targetId, sourceId, RELATION_FRIEND);
        relationRepository.deleteFriendRequestsBetween(sourceId, targetId);
        relationRepository.deleteFriendRequestsBetween(targetId, sourceId);
        relationRepository.deleteFollower(targetId, sourceId);
        relationRepository.deleteFollower(sourceId, targetId);
        adjacencyCachePort.removeFollow(sourceId, targetId);
        adjacencyCachePort.removeFollow(targetId, sourceId);
        relationEventPort.onBlock(sourceId, targetId);
        return OperationResultVO.builder()
                .success(true)
                .id(socialIdPort.nextId())
                .status("BLOCKED")
                .message("已屏蔽并清理关注/好友")
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RelationGroupVO manageGroup(Long userId,
                                       String action,
                                       String listName,
                                       Long listId,
                                       List<Long> memberIds,
                                       Long sourceListId,
                                       Long targetListId,
                                       List<Long> addMemberIds,
                                       List<Long> removeMemberIds,
                                       String idempotentToken) {
        String normalizedAction = normalizeAction(action);
        Long id = listId != null ? listId : socialIdPort.nextId();
        List<Long> members = safeMembers(memberIds);
        if (members.size() > 1000) {
            return RelationGroupVO.builder().listId(id).listName("成员过多").memberIds(Collections.emptyList()).build();
        }
        RelationGroupVO idemResult = relationGroupLockPort.loadResult(idempotentToken);
        if (idemResult != null) {
            return idemResult;
        }
        String name = defaultListName(listName);
        if (!relationGroupLockPort.tryLock(userId, normalizedAction, 5)) {
            return RelationGroupVO.builder().listId(id).listName("操作过于频繁").memberIds(Collections.emptyList()).build();
        }

        try {
            if ("DELETE".equals(normalizedAction)) {
                RelationGroupEntity deleted = relationRepository.deleteGroup(userId, id);
                RelationGroupVO vo = RelationGroupVO.builder()
                        .listId(id)
                        .listName(deleted == null ? name + " (不存在)" : name + " (已删除)")
                        .memberIds(Collections.emptyList())
                        .build();
                cacheIdem(idempotentToken, vo);
                return vo;
            }
            if (ACTION_MOVE.equals(normalizedAction)) {
                List<Long> moveList = safeMembers(memberIds);
                if (targetListId == null || sourceListId == null) {
                    return RelationGroupVO.builder().listId(id).listName("缺少分组ID").memberIds(Collections.emptyList()).build();
                }
                relationRepository.removeGroupMembers(sourceListId, moveList);
                relationRepository.addGroupMembers(targetListId, moveList);
                List<Long> targetMembers = relationRepository.listGroupMembers(targetListId);
                if (targetMembers.size() > 1000) {
                    return RelationGroupVO.builder().listId(targetListId).listName("目标成员过多").memberIds(Collections.emptyList()).build();
                }
                RelationGroupVO vo = RelationGroupVO.builder()
                        .listId(targetListId)
                        .listName(name)
                        .memberIds(targetMembers)
                        .build();
                cacheIdem(idempotentToken, vo);
                return vo;
            }
            if (ACTION_MERGE.equals(normalizedAction)) {
                Long target = targetListId != null ? targetListId : id;
                Set<Long> merged = new LinkedHashSet<>(relationRepository.listGroupMembers(target));
                merged.addAll(safeMembers(addMemberIds));
                merged.removeAll(safeMembers(removeMemberIds));
                if (merged.size() > 1000) {
                    return RelationGroupVO.builder().listId(target).listName("目标成员过多").memberIds(Collections.emptyList()).build();
                }
                relationRepository.removeGroupMembers(target, safeMembers(removeMemberIds));
                relationRepository.addGroupMembers(target, safeMembers(addMemberIds));
                RelationGroupVO vo = RelationGroupVO.builder()
                        .listId(target)
                        .listName(name)
                        .memberIds(new ArrayList<>(merged))
                        .build();
                cacheIdem(idempotentToken, vo);
                return vo;
            }
            if ("UPDATE".equals(normalizedAction)) {
                RelationGroupEntity group = RelationGroupEntity.builder()
                        .groupId(id)
                        .userId(userId)
                        .groupName(name)
                        .memberIds(members)
                        .deleted(false)
                        .build();
                relationRepository.updateGroup(group);
                relationRepository.replaceGroupMembers(id, members);
            } else if ("ADD_MEMBER".equals(normalizedAction)) {
                relationRepository.addGroupMembers(id, members);
            } else if ("REMOVE_MEMBER".equals(normalizedAction)) {
                relationRepository.removeGroupMembers(id, members);
            } else if ("LIST".equals(normalizedAction)) {
                List<RelationGroupEntity> groups = relationRepository.listGroups(userId);
                if (groups.isEmpty()) {
                    members = members.isEmpty() ? sampleMembers(userId) : members;
                    RelationGroupVO vo = RelationGroupVO.builder()
                            .listId(id)
                            .listName(name)
                            .memberIds(members)
                            .build();
                    cacheIdem(idempotentToken, vo);
                    return vo;
                }
                RelationGroupEntity first = groups.get(0);
                List<Long> storedMembers = relationRepository.listGroupMembers(first.getGroupId());
                RelationGroupVO vo = RelationGroupVO.builder()
                        .listId(first.getGroupId())
                        .listName(first.getGroupName())
                        .memberIds(storedMembers)
                        .build();
                cacheIdem(idempotentToken, vo);
                return vo;
            } else {
                RelationGroupEntity group = RelationGroupEntity.builder()
                        .groupId(id)
                        .userId(userId)
                        .groupName(name)
                        .memberIds(members)
                        .deleted(false)
                        .build();
                relationRepository.createGroup(group);
                relationRepository.replaceGroupMembers(id, members);
            }

            RelationGroupVO vo = RelationGroupVO.builder()
                    .listId(id)
                    .listName(name)
                    .memberIds(members)
                    .build();
            cacheIdem(idempotentToken, vo);
            return vo;
        } finally {
            relationGroupLockPort.unlock(userId, normalizedAction);
        }
    }

    private boolean invalidPair(Long sourceId, Long targetId) {
        return sourceId == null || targetId == null || sourceId <= 0 || targetId <= 0 || Objects.equals(sourceId, targetId);
    }

    private void cacheIdem(String token, RelationGroupVO vo) {
        relationGroupLockPort.saveResult(token, vo, 300);
    }

    private boolean reachFollowLimit(Long sourceId) {
        if (sourceId == null) {
            return true;
        }
        long cached = relationCachePort.getFollowCount(sourceId);
        return cached >= 5000;
    }

    private boolean blockedPair(Long sourceId, Long targetId) {
        RelationEntity forwardBlock = relationRepository.findRelation(sourceId, targetId, RELATION_BLOCK);
        RelationEntity reverseBlock = relationRepository.findRelation(targetId, sourceId, RELATION_BLOCK);
        return forwardBlock != null || reverseBlock != null;
    }

    private boolean isPrivateAccount(Long targetId) {
        return targetId != null && targetId % 2 != 0;
    }

    private long deterministicEdgeId(Long sourceId, Long targetId) {
        long safeSource = sourceId == null ? 0 : sourceId;
        long safeTarget = targetId == null ? 0 : targetId;
        return Math.abs(safeSource * 37 + safeTarget);
    }

    private String normalizeAction(String action) {
        return action == null ? "CREATE" : action.trim().toUpperCase();
    }

    private String defaultListName(String listName) {
        return listName == null || listName.isBlank() ? "默认分组" : listName;
    }

    private List<Long> safeMembers(List<Long> memberIds) {
        if (memberIds == null) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> dedup = new LinkedHashSet<>(memberIds);
        return new ArrayList<>(dedup);
    }

    private List<Long> sampleMembers(Long userId) {
        long base = userId == null ? socialIdPort.nextId() : userId;
        return List.of(base + 1, base + 2, base + 3);
    }

    private String toStatus(Integer statusCode) {
        if (statusCode == null) {
            return "INVALID";
        }
        return switch (statusCode) {
            case STATUS_ACTIVE -> "ACTIVE";
            case STATUS_PENDING -> "PENDING";
            case STATUS_REJECTED -> "REJECTED";
            default -> "INVALID";
        };
    }
}
