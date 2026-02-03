package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserPrivacyDao {

    UserPrivacyPO selectByUserId(@Param("userId") Long userId);

    int upsertNeedApproval(@Param("userId") Long userId, @Param("needApproval") Boolean needApproval);
}
