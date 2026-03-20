package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthAccountPO;
import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthAccountDao {

    AuthAccountPO selectByPhone(@Param("phone") String phone);

    AuthAccountPO selectByUserId(@Param("userId") Long userId);

    List<AuthAccountPO> selectByUserIds(@Param("userIds") List<Long> userIds);

    int insert(AuthAccountPO po);

    int updatePassword(@Param("userId") Long userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("passwordUpdatedAt") Date passwordUpdatedAt);

    int touchLastLogin(@Param("userId") Long userId,
                       @Param("lastLoginAt") Date lastLoginAt);
}
