package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.FriendRequestEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.entity.RelationGroupEntity;

import java.util.List;

/**
 * 关系仓储接口，抽象底层存储。
 */
public interface IRelationRepository {

    RelationEntity saveRelation(RelationEntity relation);

    RelationEntity findRelation(Long sourceId, Long targetId, Integer relationType);

    void deleteRelation(Long sourceId, Long targetId, Integer relationType);

    List<RelationEntity> listRelationsBySource(Long sourceId, Integer relationType);

    List<RelationEntity> listRelationsByTarget(Long targetId, Integer relationType);

    int countRelationsByTarget(Long targetId, Integer relationType);

    int countRelationsBySource(Long sourceId, Integer relationType);

    void saveFollower(Long id, Long userId, Long followerId);

    void deleteFollower(Long userId, Long followerId);

    /**
     * 分页查询某个用户的粉丝 ID 列表（反向表：谁关注了我）。
     *
     * @param userId  被关注者 ID
     * @param offset 偏移量（从 0 开始）
     * @param limit  单页数量
     * @return 粉丝 ID 列表
     */
    List<Long> listFollowerIds(Long userId, Integer offset, Integer limit);

    FriendRequestEntity saveFriendRequest(FriendRequestEntity request);

    FriendRequestEntity findFriendRequest(Long requestId);

    java.util.List<FriendRequestEntity> listFriendRequests(java.util.List<Long> requestIds);

    FriendRequestEntity findPendingFriendRequest(Long sourceId, Long targetId);

    boolean updateFriendRequestStatus(Long requestId, Integer status);

    int updateFriendRequestsStatus(java.util.List<Long> requestIds, Integer status);

    void deleteFriendRequestsBetween(Long sourceId, Long targetId);

    RelationGroupEntity createGroup(RelationGroupEntity group);

    RelationGroupEntity updateGroup(RelationGroupEntity group);

    RelationGroupEntity deleteGroup(Long userId, Long groupId);

    List<RelationGroupEntity> listGroups(Long userId);

    void replaceGroupMembers(Long groupId, java.util.List<Long> memberIds);

    java.util.List<Long> listGroupMembers(Long groupId);

    void addGroupMembers(Long groupId, java.util.List<Long> memberIds);

    void removeGroupMembers(Long groupId, java.util.List<Long> memberIds);
}
