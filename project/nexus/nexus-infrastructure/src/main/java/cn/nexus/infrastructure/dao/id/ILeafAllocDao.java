package cn.nexus.infrastructure.dao.id;

import cn.nexus.infrastructure.dao.id.po.LeafAllocPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ILeafAllocDao {

    LeafAllocPO selectByBizTagForUpdate(@Param("bizTag") String bizTag);

    int insert(LeafAllocPO po);

    int updateMaxId(@Param("bizTag") String bizTag, @Param("maxId") Long maxId);
}
