package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RelationGroupMemberPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IRelationGroupMemberDao {

    int deleteByGroup(@Param("groupId") Long groupId);

    int batchInsert(@Param("list") List<RelationGroupMemberPO> list);

    List<RelationGroupMemberPO> selectByGroup(@Param("groupId") Long groupId);

    int deleteBatch(@Param("groupId") Long groupId, @Param("memberIds") List<Long> memberIds);
}
