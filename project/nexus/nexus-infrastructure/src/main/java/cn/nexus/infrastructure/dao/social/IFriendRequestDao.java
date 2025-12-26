package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.FriendRequestPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFriendRequestDao {

    int insert(FriendRequestPO po);

    FriendRequestPO selectById(@Param("requestId") Long requestId);

    FriendRequestPO selectPending(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);

    int updateStatusIfPending(@Param("requestId") Long requestId, @Param("status") Integer status);

    int deleteBetween(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
