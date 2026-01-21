package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserBaseDao {
    List<UserBasePO> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<UserBasePO> selectByUsernames(@Param("usernames") List<String> usernames);
}
