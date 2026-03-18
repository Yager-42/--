package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthRolePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthRoleDao {

    AuthRolePO selectByRoleCode(@Param("roleCode") String roleCode);
}
