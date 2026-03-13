package cn.nexus.infrastructure.dao.user;

import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserStatusDao {

    UserStatusPO selectByUserId(@Param("userId") Long userId);

    int upsert(UserStatusPO po);
}

