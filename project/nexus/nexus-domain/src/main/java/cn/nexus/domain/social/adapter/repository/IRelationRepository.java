package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RelationEntity;
import java.util.Date;
import java.util.List;

public interface IRelationRepository {

    RelationEntity saveRelation(RelationEntity relation);

    RelationEntity findRelation(Long sourceId, Long targetId, Integer relationType);

    void deleteRelation(Long sourceId, Long targetId, Integer relationType);

    List<RelationEntity> listRelationsBySource(Long sourceId, Integer relationType);

    List<RelationEntity> listRelationsByTarget(Long targetId, Integer relationType);

    int countRelationsByTarget(Long targetId, Integer relationType);

    int countRelationsBySource(Long sourceId, Integer relationType);

    int countActiveRelationsBySource(Long sourceId, Integer relationType);

    int countActiveRelationsByTarget(Long targetId, Integer relationType);

    void saveFollower(Long id, Long userId, Long followerId);

    void deleteFollower(Long userId, Long followerId);

    List<Long> listFollowerIds(Long userId, Integer offset, Integer limit);

    int countFollowerIds(Long userId);

    List<Long> listBigVFollowingIds(Long userId, int followerThreshold, int limit);

    List<RelationEntity> pageActiveFollowsBySource(Long sourceId, Date cursorTime, Long cursorTargetId, int limit);

    List<RelationEntity> pageActiveFollowsByTarget(Long targetId, Date cursorTime, Long cursorSourceId, int limit);
}
