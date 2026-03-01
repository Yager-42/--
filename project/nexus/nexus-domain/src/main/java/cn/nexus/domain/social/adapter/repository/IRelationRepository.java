package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.FriendRequestEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;

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

    /**
     * 查询某个用户的粉丝数量（反向表计数：谁关注了我）。
     *
     * <p>用于 fanout 大任务切片：dispatcher 需要用粉丝总数计算切片数量。</p>
     *
     * @param userId 被关注者 ID
     * @return 粉丝数量（>=0）
     */
    int countFollowerIds(Long userId);

    /**
     * 只返回“大 V”关注对象：粉丝数 >= 阈值的 followingIds。
     *
     * <p>用于 FOLLOW 首页兜底：当关注列表来自缓存且可能被截断时，仅回源补齐大 V，避免回源全量关注。</p>
     *
     * @param userId            发起方（我）的用户 ID
     * @param followerThreshold 粉丝阈值（>= threshold 视为大 V）
     * @param limit             最多返回数量
     * @return 大 V 的 targetId 列表（可空列表）
     */
    List<Long> listBigVFollowingIds(Long userId, int followerThreshold, int limit);

    FriendRequestEntity saveFriendRequest(FriendRequestEntity request);

    FriendRequestEntity findFriendRequest(Long requestId);

    java.util.List<FriendRequestEntity> listFriendRequests(java.util.List<Long> requestIds);

    FriendRequestEntity findPendingFriendRequest(Long sourceId, Long targetId);

    boolean updateFriendRequestStatus(Long requestId, Integer status);

    int updateFriendRequestsStatus(java.util.List<Long> requestIds, Integer status);

    void deleteFriendRequestsBetween(Long sourceId, Long targetId);
}
