package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthSmsCodePO;
import java.util.Date;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthSmsCodeDao {

    int invalidateLatest(@Param("phone") String phone, @Param("bizType") String bizType);

    int insert(AuthSmsCodePO po);

    AuthSmsCodePO selectLatestActive(@Param("phone") String phone, @Param("bizType") String bizType);

    int incrementVerifyFail(@Param("id") Long id);

    int markUsed(@Param("id") Long id, @Param("usedAt") Date usedAt);
}
