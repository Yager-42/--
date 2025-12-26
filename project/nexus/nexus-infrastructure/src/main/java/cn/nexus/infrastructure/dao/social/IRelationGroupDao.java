package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationGroupPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRelationGroupDao {

    int insert(RelationGroupPO po);

    int update(RelationGroupPO po);

    int softDelete(@Param("userId") Long userId, @Param("groupId") Long groupId);

    List<RelationGroupPO> selectByUser(@Param("userId") Long userId);
}
