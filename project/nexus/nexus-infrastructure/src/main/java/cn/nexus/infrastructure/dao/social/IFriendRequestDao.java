package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.FriendRequestPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IFriendRequestDao {

    int insert(FriendRequestPO po);

    FriendRequestPO selectById(@Param("requestId") Long requestId);

    java.util.List<FriendRequestPO> selectByIds(@Param("requestIds") java.util.List<Long> requestIds);

    FriendRequestPO selectPending(@Param("idempotentKey") String idempotentKey);

    int updateStatusIfPending(@Param("requestId") Long requestId, @Param("status") Integer status);

    int updateStatusIfPendingBatch(@Param("requestIds") java.util.List<Long> requestIds, @Param("status") Integer status);

    int deleteBetween(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
