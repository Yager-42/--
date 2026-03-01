package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.FriendRequestEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.infrastructure.dao.social.IFollowerDao;
import cn.nexus.infrastructure.dao.social.IFriendRequestDao;
import cn.nexus.infrastructure.dao.social.IRelationDao;
import cn.nexus.infrastructure.dao.social.po.FollowerPO;
import cn.nexus.infrastructure.dao.social.po.FriendRequestPO;
import cn.nexus.infrastructure.dao.social.po.RelationPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 关系仓储 MyBatis 实现，对接 MySQL。
 */
@Repository
@RequiredArgsConstructor
public class RelationRepository implements IRelationRepository {

    private static final int RELATION_FOLLOW = 1;
    private static final int STATUS_ACTIVE = 1;

    private final IRelationDao relationDao;
    private final IFriendRequestDao friendRequestDao;
    private final IFollowerDao followerDao;

    @Override
    public RelationEntity saveRelation(RelationEntity relation) {
        RelationPO po = toPO(relation);
        relationDao.insertOrUpdate(po);
        return relation;
    }

    @Override
    public RelationEntity findRelation(Long sourceId, Long targetId, Integer relationType) {
        RelationPO po = relationDao.selectOne(sourceId, targetId, relationType);
        return toEntity(po);
    }

    @Override
    public void deleteRelation(Long sourceId, Long targetId, Integer relationType) {
        relationDao.delete(sourceId, targetId, relationType);
    }

    @Override
    public List<RelationEntity> listRelationsBySource(Long sourceId, Integer relationType) {
        List<RelationPO> list = relationDao.selectBySource(sourceId, relationType);
        return list.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<RelationEntity> listRelationsByTarget(Long targetId, Integer relationType) {
        List<RelationPO> list = relationDao.selectByTarget(targetId, relationType);
        return list.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public int countRelationsByTarget(Long targetId, Integer relationType) {
        Integer cnt = relationDao.countByTarget(targetId, relationType);
        return cnt == null ? 0 : cnt;
    }

    @Override
    public int countRelationsBySource(Long sourceId, Integer relationType) {
        Integer cnt = relationDao.countBySource(sourceId, relationType);
        return cnt == null ? 0 : cnt;
    }

    @Override
    public void saveFollower(Long id, Long userId, Long followerId) {
        FollowerPO po = new FollowerPO();
        po.setId(id);
        po.setUserId(userId);
        po.setFollowerId(followerId);
        followerDao.insert(po);
    }

    @Override
    public void deleteFollower(Long userId, Long followerId) {
        followerDao.delete(userId, followerId);
    }

    @Override
    public java.util.List<Long> listFollowerIds(Long userId, Integer offset, Integer limit) {
        return followerDao.selectFollowerIds(userId, offset, limit);
    }

    /**
     * 查询某个用户的粉丝数量（反向表计数：谁关注了我）。
     *
     * @param userId 被关注者 ID
     * @return 粉丝数量（>=0）
     */
    @Override
    public int countFollowerIds(Long userId) {
        if (userId == null) {
            return 0;
        }
        return followerDao.countFollowers(userId);
    }

    @Override
    public List<Long> listBigVFollowingIds(Long userId, int followerThreshold, int limit) {
        if (userId == null || followerThreshold <= 0 || limit <= 0) {
            return List.of();
        }
        List<Long> list = relationDao.selectBigVFollowingIds(userId, RELATION_FOLLOW, STATUS_ACTIVE, followerThreshold, limit);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public FriendRequestEntity saveFriendRequest(FriendRequestEntity request) {
        FriendRequestPO po = new FriendRequestPO();
        po.setRequestId(request.getRequestId());
        po.setSourceId(request.getSourceId());
        po.setTargetId(request.getTargetId());
        po.setIdempotentKey(request.getIdempotentKey());
        po.setStatus(request.getStatus());
        po.setVersion(request.getVersion());
        friendRequestDao.insert(po);
        return request;
    }

    @Override
    public FriendRequestEntity findFriendRequest(Long requestId) {
        FriendRequestPO po = friendRequestDao.selectById(requestId);
        return toEntity(po);
    }

    @Override
    public java.util.List<FriendRequestEntity> listFriendRequests(java.util.List<Long> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<FriendRequestPO> list = friendRequestDao.selectByIds(requestIds);
        return list.stream().map(this::toEntity).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public FriendRequestEntity findPendingFriendRequest(Long sourceId, Long targetId) {
        FriendRequestPO po = friendRequestDao.selectPending(idempotentKey(sourceId, targetId));
        return toEntity(po);
    }

    @Override
    public boolean updateFriendRequestStatus(Long requestId, Integer status) {
        FriendRequestPO po = friendRequestDao.selectById(requestId);
        if (po == null) {
            return false;
        }
        return friendRequestDao.updateStatusIfPending(requestId, status) > 0;
    }

    @Override
    public int updateFriendRequestsStatus(java.util.List<Long> requestIds, Integer status) {
        if (requestIds == null || requestIds.isEmpty()) {
            return 0;
        }
        return friendRequestDao.updateStatusIfPendingBatch(requestIds, status);
    }

    @Override
    public void deleteFriendRequestsBetween(Long sourceId, Long targetId) {
        friendRequestDao.deleteBetween(sourceId, targetId);
    }

    private String idempotentKey(Long sourceId, Long targetId) {
        long safeSource = sourceId == null ? 0 : sourceId;
        long safeTarget = targetId == null ? 0 : targetId;
        return safeSource + "-" + safeTarget;
    }

    private RelationEntity toEntity(RelationPO po) {
        if (po == null) {
            return null;
        }
        return RelationEntity.builder()
                .id(po.getId())
                .sourceId(po.getSourceId())
                .targetId(po.getTargetId())
                .relationType(po.getRelationType())
                .status(po.getStatus())
                .groupId(po.getGroupId())
                .version(po.getVersion())
                .build();
    }

    private RelationPO toPO(RelationEntity entity) {
        RelationPO po = new RelationPO();
        po.setId(entity.getId());
        po.setSourceId(entity.getSourceId());
        po.setTargetId(entity.getTargetId());
        po.setRelationType(entity.getRelationType());
        po.setStatus(entity.getStatus());
        po.setGroupId(entity.getGroupId());
        po.setVersion(entity.getVersion());
        return po;
    }

    private FriendRequestEntity toEntity(FriendRequestPO po) {
        if (po == null) {
            return null;
        }
        return FriendRequestEntity.builder()
                .requestId(po.getRequestId())
                .sourceId(po.getSourceId())
                .targetId(po.getTargetId())
                .idempotentKey(po.getIdempotentKey())
                .status(po.getStatus())
                .version(po.getVersion())
                .build();
    }
}
