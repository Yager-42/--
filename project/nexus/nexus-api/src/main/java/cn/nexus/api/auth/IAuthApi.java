package cn.nexus.api.auth;

import cn.nexus.api.auth.dto.AuthLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthLoginResponseDTO;
import cn.nexus.api.response.Response;

/**
 * Authentication API.
 */
public interface IAuthApi {

    Response<AuthLoginResponseDTO> login(AuthLoginRequestDTO requestDTO);
}
