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

    int delete(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId, @Param("relationType") Integer relationType);

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

    List<RelationPO> pageActiveByTarget(@Param("targetId") Long targetId,
                                        @Param("relationType") Integer relationType,
                                        @Param("status") Integer status,
                                        @Param("cursorTime") Date cursorTime,
                                        @Param("cursorSourceId") Long cursorSourceId,
                                        @Param("limit") Integer limit);
}
