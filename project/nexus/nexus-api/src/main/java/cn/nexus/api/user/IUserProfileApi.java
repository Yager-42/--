package cn.nexus.api.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;
import cn.nexus.api.user.dto.UserProfileResponseDTO;
import cn.nexus.api.user.dto.UserProfileUpdateRequestDTO;

/**
 * 用户 Profile API：名片读写。
 */
public interface IUserProfileApi {

    Response<UserProfileResponseDTO> myProfile();

    Response<UserProfileResponseDTO> profile(UserProfileQueryRequestDTO requestDTO);

    Response<OperationResultDTO> updateMyProfile(UserProfileUpdateRequestDTO requestDTO);
}
