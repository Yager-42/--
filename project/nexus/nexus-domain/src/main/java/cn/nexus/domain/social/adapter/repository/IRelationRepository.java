package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RelationEntity;
import java.util.Date;
import java.util.List;

/**
 * 关系仓储：封装关注、拉黑和粉丝视图相关的持久化操作。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
public interface IRelationRepository {

    /**
     * 保存一条关系边。
     *
     * @param relation 关系实体，类型：{@link RelationEntity}
     * @return 持久化后的关系实体，类型：{@link RelationEntity}
     */
    RelationEntity saveRelation(RelationEntity relation);

    /**
     * 查询指定关系边。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 命中的关系实体；不存在时返回 `null`，类型：{@link RelationEntity}
     */
    RelationEntity findRelation(Long sourceId, Long targetId, Integer relationType);

    RelationEntity findRelationForUpdate(Long sourceId, Long targetId, Integer relationType);

    boolean activateRelation(Long sourceId, Long targetId, Integer relationType, Long expectedVersion, Date createTime);

    boolean deactivateRelation(Long sourceId, Long targetId, Integer relationType, Long expectedVersion, Integer inactiveStatus);

    /**
     * 按发起方查询关系边列表。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 关系边列表，类型：{@link List}&lt;{@link RelationEntity}&gt;
     */
    List<RelationEntity> listRelationsBySource(Long sourceId, Integer relationType);

    /**
     * 按目标方查询关系边列表。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 关系边列表，类型：{@link List}&lt;{@link RelationEntity}&gt;
     */
    List<RelationEntity> listRelationsByTarget(Long targetId, Integer relationType);

    /**
     * 统计指向目标用户的关系数量。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 关系数量，类型：{@code int}
     */
    int countRelationsByTarget(Long targetId, Integer relationType);

    /**
     * 统计由发起方发出的关系数量。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 关系数量，类型：{@code int}
     */
    int countRelationsBySource(Long sourceId, Integer relationType);

    /**
     * 统计发起方的有效关系数量。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 有效关系数量，类型：{@code int}
     */
    int countActiveRelationsBySource(Long sourceId, Integer relationType);

    /**
     * 统计目标方的有效关系数量。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param relationType 关系类型，类型：{@link Integer}
     * @return 有效关系数量，类型：{@code int}
     */
    int countActiveRelationsByTarget(Long targetId, Integer relationType);

    /**
     * 保存粉丝视图记录。
     *
     * @param id 记录 ID，类型：{@link Long}
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @param followerId 粉丝用户 ID，类型：{@link Long}
     * @param createTime 创建时间，类型：{@link Date}
     */
    void saveFollower(Long id, Long userId, Long followerId, Date createTime);

    /**
     * 投影层写入粉丝视图，只有从“不存在”变成“存在”时返回 true。
     *
     * @param id 记录 ID，类型：{@link Long}
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @param followerId 粉丝用户 ID，类型：{@link Long}
     * @param createTime 创建时间，类型：{@link Date}
     * @return 是否发生了真实状态迁移（inactive -> active），类型：{@code boolean}
     */
    boolean saveFollowerIfAbsent(Long id, Long userId, Long followerId, Date createTime);

    /**
     * 删除粉丝视图记录。
     *
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @param followerId 粉丝用户 ID，类型：{@link Long}
     */
    void deleteFollower(Long userId, Long followerId);

    /**
     * 投影层删除粉丝视图，只有从“存在”变成“不存在”时返回 true。
     *
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @param followerId 粉丝用户 ID，类型：{@link Long}
     * @return 是否发生了真实状态迁移（active -> inactive），类型：{@code boolean}
     */
    boolean deleteFollowerIfPresent(Long userId, Long followerId);

    /**
     * 仅供 Feed fanout 批处理使用的粉丝 ID 扫描接口。
     *
     * <p>这里保留 offset 语义是为了内部切片写扩散，不能拿去实现用户可见的 followers 分页。</p>
     *
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @param offset 批处理偏移量，类型：{@link Integer}
     * @param limit 本次扫描上限，类型：{@link Integer}
     * @return 粉丝用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     */
    List<Long> pageFollowerIdsForFanout(Long userId, Integer offset, Integer limit);

    /**
     * 统计粉丝视图总数。
     *
     * @param userId 被关注用户 ID，类型：{@link Long}
     * @return 粉丝数量，类型：{@code int}
     */
    int countFollowerIds(Long userId);

    /**
     * 查询大 V 关注列表，用于推荐和扩散场景。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param followerThreshold 大 V 粉丝阈值，类型：{@code int}
     * @param limit 返回上限，类型：{@code int}
     * @return 大 V 关注用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     */
    List<Long> listBigVFollowingIds(Long userId, int followerThreshold, int limit);

    /**
     * 分页扫描发起方的有效关注边。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param cursorTime 游标时间，类型：{@link Date}
     * @param cursorTargetId 游标目标用户 ID，类型：{@link Long}
     * @param limit 返回上限，类型：{@code int}
     * @return 关系边列表，类型：{@link List}&lt;{@link RelationEntity}&gt;
     */
    List<RelationEntity> pageActiveFollowsBySource(Long sourceId, Date cursorTime, Long cursorTargetId, int limit);

    /**
     * 分页扫描目标方收到的有效关注边。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param cursorTime 游标时间，类型：{@link Date}
     * @param cursorSourceId 游标发起方用户 ID，类型：{@link Long}
     * @param limit 返回上限，类型：{@code int}
     * @return 关系边列表，类型：{@link List}&lt;{@link RelationEntity}&gt;
     */
    List<RelationEntity> pageActiveFollowsByTarget(Long targetId, Date cursorTime, Long cursorSourceId, int limit);

    /**
     * 批量查询发起方对目标集合的有效关注目标。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param targetIds 目标用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 已关注目标 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     */
    List<Long> batchFindActiveFollowTargets(Long sourceId, List<Long> targetIds);

    /**
     * 批量查询发起方拉黑的目标集合。
     *
     * @param sourceId 发起方用户 ID，类型：{@link Long}
     * @param targetIds 目标用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 被拉黑目标 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     */
    List<Long> batchFindBlockTargetsBySource(Long sourceId, List<Long> targetIds);

    /**
     * 批量查询哪些来源用户被目标方拉黑。
     *
     * @param targetId 目标用户 ID，类型：{@link Long}
     * @param sourceIds 来源用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 被目标拉黑的来源用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     */
    List<Long> batchFindBlockSourcesByTarget(Long targetId, List<Long> sourceIds);
}
