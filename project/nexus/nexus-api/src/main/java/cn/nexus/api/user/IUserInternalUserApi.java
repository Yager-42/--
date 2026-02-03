package cn.nexus.api.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserInternalUpsertRequestDTO;

/**
 * 用户域 internal 写入口（给网关/系统调用）：update-only，不负责创建用户。
 */
public interface IUserInternalUserApi {

    Response<OperationResultDTO> upsert(UserInternalUpsertRequestDTO requestDTO);
}

