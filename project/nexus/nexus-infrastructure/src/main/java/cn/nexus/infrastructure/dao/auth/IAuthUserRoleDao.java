package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthUserRolePO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthUserRoleDao {

    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    List<Long> selectUserIdsByRoleCode(@Param("roleCode") String roleCode);

    int insertIgnore(AuthUserRolePO po);

    int deleteByUserIdAndRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);

    int countByUserIdAndRoleCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
