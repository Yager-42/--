package cn.nexus.api.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserInternalUpsertRequestDTO;

/**
 * 用户域 internal 写入口（给网关/系统调用）：update-only，不负责创建用户。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>请求体可传 {@code status=DEACTIVATED} 来停用用户（停用后：普通写接口应返回 {@code 0410 USER_DEACTIVATED}）。</li>
 * </ul>
 */
public interface IUserInternalUserApi {

    Response<OperationResultDTO> upsert(UserInternalUpsertRequestDTO requestDTO);
}

