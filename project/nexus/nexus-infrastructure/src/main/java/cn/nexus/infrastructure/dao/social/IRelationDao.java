package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IRelationDao {

    int insertOrUpdate(RelationPO po);

    RelationPO selectOne(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    RelationPO selectOneForUpdate(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    int activate(@Param("sourceId") Long sourceId,
                 @Param("targetId") Long targetId,
                 @Param("relationType") Integer relationType,
                 @Param("expectedVersion") Long expectedVersion,
                 @Param("activeStatus") Integer activeStatus,
                 @Param("inactiveStatus") Integer inactiveStatus,
                 @Param("createTime") Date createTime);

    int deactivate(@Param("sourceId") Long sourceId,
                   @Param("targetId") Long targetId,
                   @Param("relationType") Integer relationType,
                   @Param("expectedVersion") Long expectedVersion,
                   @Param("activeStatus") Integer activeStatus,
                   @Param("inactiveStatus") Integer inactiveStatus);

    List<RelationPO> selectBySource(@Param("sourceId") Long sourceId, @Param("relationType") Integer relationType);

    Integer countBySource(@Param("sourceId") Long sourceId, @Param("relationType") Integer relationType);

    Integer countActiveBySource(@Param("sourceId") Long sourceId,
                                @Param("relationType") Integer relationType,
                                @Param("status") Integer status);

    List<RelationPO> selectByTarget(@Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    Integer countByTarget(@Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    Integer countActiveByTarget(@Param("targetId") Long targetId,
                                @Param("relationType") Integer relationType,
                                @Param("status") Integer status);

    List<Long> selectBigVFollowingIds(@Param("sourceId") Long sourceId,
                                      @Param("relationType") Integer relationType,
                                      @Param("status") Integer status,
                                      @Param("followerThreshold") Integer followerThreshold,
                                      @Param("limit") Integer limit);

    List<RelationPO> pageActiveBySource(@Param("sourceId") Long sourceId,
                                        @Param("relationType") Integer relationType,
                                        @Param("status") Integer status,
                                        @Param("cursorTime") Date cursorTime,
                                        @Param("cursorTargetId") Long cursorTargetId,
                                        @Param("limit") Integer limit);

    /**
     * 用户可见的 followers keyset 分页主路径。
     *
     * <p>这个查询的语义固定为：按 {@code create_time DESC, source_id DESC} 做 keyset 分页。
     * 它不是 offset 深分页替代品；如果目标侧缺少覆盖
     * {@code target_id + relation_type + status + create_time + source_id} 的复合索引，
     * 就不能把这条路径视为可安全上线的最终方案。</p>
     */
    List<RelationPO> pageActiveByTarget(@Param("targetId") Long targetId,
                                        @Param("relationType") Integer relationType,
                                        @Param("status") Integer status,
                                        @Param("cursorTime") Date cursorTime,
                                        @Param("cursorSourceId") Long cursorSourceId,
                                        @Param("limit") Integer limit);

    List<Long> selectTargetIdsBySourceAndType(@Param("sourceId") Long sourceId,
                                              @Param("relationType") Integer relationType,
                                              @Param("status") Integer status,
                                              @Param("targetIds") List<Long> targetIds);

    List<Long> selectSourceIdsByTargetAndType(@Param("targetId") Long targetId,
                                              @Param("relationType") Integer relationType,
                                              @Param("status") Integer status,
                                              @Param("sourceIds") List<Long> sourceIds);
}
