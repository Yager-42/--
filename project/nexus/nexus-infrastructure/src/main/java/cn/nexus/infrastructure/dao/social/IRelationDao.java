package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRelationDao {

    int insertOrUpdate(RelationPO po);

    RelationPO selectOne(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    int delete(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    List<RelationPO> selectBySource(@Param("sourceId") Long sourceId, @Param("relationType") Integer relationType);

    Integer countBySource(@Param("sourceId") Long sourceId, @Param("relationType") Integer relationType);

    List<RelationPO> selectByTarget(@Param("targetId") Long targetId, @Param("relationType") Integer relationType);

    Integer countByTarget(@Param("targetId") Long targetId, @Param("relationType") Integer relationType);
}
