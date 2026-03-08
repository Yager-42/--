package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.infrastructure.dao.social.IFollowerDao;
import cn.nexus.infrastructure.dao.social.IRelationDao;
import cn.nexus.infrastructure.dao.social.po.FollowerPO;
import cn.nexus.infrastructure.dao.social.po.RelationPO;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RelationRepository implements IRelationRepository {

    private final IRelationDao relationDao;
    private final IFollowerDao followerDao;

    @Override
    public RelationEntity saveRelation(RelationEntity relation) {
        if (relation == null) {
            return null;
        }
        relationDao.insertOrUpdate(toPO(relation));
        return relation;
    }

    @Override
    public RelationEntity findRelation(Long sourceId, Long targetId, Integer relationType) {
        return toEntity(relationDao.selectOne(sourceId, targetId, relationType));
    }

    @Override
    public void deleteRelation(Long sourceId, Long targetId, Integer relationType) {
        relationDao.delete(sourceId, targetId, relationType);
    }

    @Override
    public List<RelationEntity> listRelationsBySource(Long sourceId, Integer relationType) {
        return relationDao.selectBySource(sourceId, relationType).stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<RelationEntity> listRelationsByTarget(Long targetId, Integer relationType) {
        return relationDao.selectByTarget(targetId, relationType).stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public int countRelationsByTarget(Long targetId, Integer relationType) {
        Integer count = relationDao.countByTarget(targetId, relationType);
        return count == null ? 0 : count;
    }

    @Override
    public int countRelationsBySource(Long sourceId, Integer relationType) {
        Integer count = relationDao.countBySource(sourceId, relationType);
        return count == null ? 0 : count;
    }

    @Override
    public int countActiveRelationsBySource(Long sourceId, Integer relationType) {
        Integer count = relationDao.countActiveBySource(sourceId, relationType, 1);
        return count == null ? 0 : count;
    }

    @Override
    public int countActiveRelationsByTarget(Long targetId, Integer relationType) {
        Integer count = relationDao.countActiveByTarget(targetId, relationType, 1);
        return count == null ? 0 : count;
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
    public List<Long> listFollowerIds(Long userId, Integer offset, Integer limit) {
        return followerDao.selectFollowerIds(userId, offset, limit);
    }

    @Override
    public int countFollowerIds(Long userId) {
        return followerDao.countFollowers(userId);
    }

    @Override
    public List<Long> listBigVFollowingIds(Long userId, int followerThreshold, int limit) {
        return relationDao.selectBigVFollowingIds(userId, 1, 1, followerThreshold, limit);
    }

    @Override
    public List<RelationEntity> pageActiveFollowsBySource(Long sourceId, Date cursorTime, Long cursorTargetId, int limit) {
        return relationDao.pageActiveBySource(sourceId, 1, 1, cursorTime, cursorTargetId, limit).stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<RelationEntity> pageActiveFollowsByTarget(Long targetId, Date cursorTime, Long cursorSourceId, int limit) {
        return relationDao.pageActiveByTarget(targetId, 1, 1, cursorTime, cursorSourceId, limit).stream()
                .map(this::toEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }


    @Override
    public List<Long> batchFindActiveFollowTargets(Long sourceId, List<Long> targetIds) {
        if (sourceId == null || targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }
        return relationDao.selectTargetIdsBySourceAndType(sourceId, 1, 1, targetIds);
    }

    @Override
    public List<Long> batchFindBlockTargetsBySource(Long sourceId, List<Long> targetIds) {
        if (sourceId == null || targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }
        return relationDao.selectTargetIdsBySourceAndType(sourceId, 3, 1, targetIds);
    }

    @Override
    public List<Long> batchFindBlockSourcesByTarget(Long targetId, List<Long> sourceIds) {
        if (targetId == null || sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        return relationDao.selectSourceIdsByTargetAndType(targetId, 3, 1, sourceIds);
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
                .createTime(po.getCreateTime())
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
}
