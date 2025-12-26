package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.FollowerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFollowerDao {

    int insert(FollowerPO po);

    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);
}
