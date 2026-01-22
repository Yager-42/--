package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.port.IRelationGroupLockPort;
import cn.nexus.domain.social.adapter.port.IFriendRequestIdempotentPort;
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
    private final IFriendRequestIdempotentPort friendRequestIdempotentPort;

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_FRIEND = 2;
    private static final int RELATION_BLOCK = 3;

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_PENDING = 2;
    private static final int STATUS_REJECTED = 3;

    private static final String ACTION_MOVE = "MOVE";
    private static final String ACTION_MERGE = "MERGE";

    /**
     * 关注流程：先校验参数与屏蔽、关注上限，再判断已有关注/好友边；根据策略决定是否进入待审批或直接生效，写关系表、粉丝表、缓存并触发关注事件。
     */
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

    /**
     * 取消关注：删除关注边与粉丝反向表记录，更新邻接缓存，并发布 UNFOLLOW 事件。
     * 
     * <p>语义：幂等。未关注时返回 NOT_FOLLOWING，不发布事件。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowResultVO unfollow(Long sourceId, Long targetId) {
        if (invalidPair(sourceId, targetId)) {
            return FollowResultVO.builder().status("INVALID").build();
        }

        RelationEntity existFollow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
        if (existFollow == null) {
            // best-effort：尽量把缓存清理干净，避免脏数据导致重建关注列表不一致
            adjacencyCachePort.removeFollow(sourceId, targetId);
            relationRepository.deleteFollower(targetId, sourceId);
            return FollowResultVO.builder().status("NOT_FOLLOWING").build();
        }

        relationRepository.deleteRelation(sourceId, targetId, RELATION_FOLLOW);
        relationRepository.deleteFollower(targetId, sourceId);
        adjacencyCachePort.removeFollow(sourceId, targetId);
        relationEventPort.onFollow(sourceId, targetId, "UNFOLLOW");
        return FollowResultVO.builder().status("UNFOLLOWED").build();
    }

    /**
     * 好友申请：校验参数与屏蔽，检查已是好友或存在待处理记录；使用幂等键防重复写，保存待审批申请并返回请求号和当前状态。
     */
    @Override
    public FriendRequestResultVO friendRequest(Long sourceId, Long targetId, String verifyMsg, String sourceChannel) {
        // 参数/屏蔽/好友/重复申请校验
        if (invalidPair(sourceId, targetId)) {
            return FriendRequestResultVO.builder().status("INVALID").build();
        }
        long idemId = deterministicEdgeId(sourceId, targetId);
        String idemKey = friendRequestKey(sourceId, targetId);
        if (blockedPair(sourceId, targetId)) {
            return FriendRequestResultVO.builder()
                    .requestId(idemId)
                    .status("BLOCKED")
                    .build();
        }
        RelationEntity friendEdge = relationRepository.findRelation(sourceId, targetId, RELATION_FRIEND);
        RelationEntity reverseFriend = relationRepository.findRelation(targetId, sourceId, RELATION_FRIEND);
        if (friendEdge != null || reverseFriend != null) {
            return FriendRequestResultVO.builder()
                    .requestId(friendEdge != null ? friendEdge.getId() : idemId)
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

        boolean acquired = friendRequestIdempotentPort.acquire(idemKey, 60);
        if (!acquired) {
            FriendRequestEntity existing = relationRepository.findFriendRequest(idemId);
            if (existing != null) {
                return FriendRequestResultVO.builder()
                        .requestId(existing.getRequestId())
                        .status(toStatus(existing.getStatus()))
                        .build();
            }
            return FriendRequestResultVO.builder()
                    .requestId(idemId)
                    .status("PENDING")
                    .build();
        }

        FriendRequestEntity entity = FriendRequestEntity.builder()
                .requestId(idemId)
                .sourceId(sourceId)
                .targetId(targetId)
                .idempotentKey(friendRequestKey(sourceId, targetId))
                .status(STATUS_PENDING)
                .version(0L)
                .build();
        relationRepository.saveFriendRequest(entity);
        return FriendRequestResultVO.builder()
                .requestId(entity.getRequestId())
                .status(toStatus(entity.getStatus()))
                .build();
    }

    /**
     * 好友审批：去重请求号后批量加载申请，全部为待处理才允许，依据 action 决定通过或拒绝；通过时写双向好友关系、粉丝表、缓存并触发事件，保持事务一致。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FriendDecisionResultVO friendDecision(List<Long> requestIds, String action) {
        if (requestIds == null || requestIds.isEmpty()) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        requestIds = new ArrayList<>(new LinkedHashSet<>(requestIds));
        boolean accept = "ACCEPT".equalsIgnoreCase(action);
        List<FriendRequestEntity> requests = relationRepository.listFriendRequests(requestIds);
        if (requests.size() != requestIds.size()) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        boolean allPending = requests.stream().allMatch(r -> Objects.equals(r.getStatus(), STATUS_PENDING));
        if (!allPending) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        int updated = relationRepository.updateFriendRequestsStatus(requestIds, accept ? STATUS_ACTIVE : STATUS_REJECTED);
        if (updated != requestIds.size()) {
            return FriendDecisionResultVO.builder().success(false).build();
        }
        if (!accept) {
            return FriendDecisionResultVO.builder().success(true).build();
        }
        for (FriendRequestEntity request : requests) {
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
        }
        return FriendDecisionResultVO.builder().success(true).build();
    }

    /**
     * 屏蔽：创建屏蔽边，同时删除双方关注/好友及待处理申请、粉丝记录，清理缓存并触发屏蔽事件，返回操作结果。
     */
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

    /**
     * 关系分组管理：基于 action 执行创建/更新/删除/移动/合并/成员增删/查询。先做成员数量与幂等结果检查，再加分布式锁防并发；操作完成缓存幂等结果并释放锁。
     */
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

    private String friendRequestKey(Long sourceId, Long targetId) {
        long safeSource = sourceId == null ? 0 : sourceId;
        long safeTarget = targetId == null ? 0 : targetId;
        return safeSource + "-" + safeTarget;
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
