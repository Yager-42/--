package cn.nexus.api.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserPrivacyResponseDTO;
import cn.nexus.api.user.dto.UserPrivacyUpdateRequestDTO;

/**
 * 用户设置 API：隐私开关。
 */
public interface IUserSettingApi {

    Response<UserPrivacyResponseDTO> myPrivacy();

    Response<OperationResultDTO> updateMyPrivacy(UserPrivacyUpdateRequestDTO requestDTO);
}
