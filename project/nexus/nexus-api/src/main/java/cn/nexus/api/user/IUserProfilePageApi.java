package cn.nexus.api.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.user.dto.UserProfilePageResponseDTO;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;

/**
 * 用户个人主页聚合 API：用于个人主页展示的聚合读接口。
 */
public interface IUserProfilePageApi {

    Response<UserProfilePageResponseDTO> profilePage(UserProfileQueryRequestDTO requestDTO);
}

