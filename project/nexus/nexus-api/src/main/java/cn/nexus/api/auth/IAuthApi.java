package cn.nexus.api.auth;

import cn.nexus.api.auth.dto.AuthAdminListResponseDTO;
import cn.nexus.api.auth.dto.AuthChangePasswordRequestDTO;
import cn.nexus.api.auth.dto.AuthGrantAdminRequestDTO;
import cn.nexus.api.auth.dto.AuthMeResponseDTO;
import cn.nexus.api.auth.dto.AuthPasswordLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthRefreshRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterResponseDTO;
import cn.nexus.api.auth.dto.AuthTokenResponseDTO;
import cn.nexus.api.response.Response;

/**
 * 正式认证 API 契约。
 */
public interface IAuthApi {

    Response<AuthRegisterResponseDTO> register(AuthRegisterRequestDTO requestDTO);

    Response<AuthTokenResponseDTO> passwordLogin(AuthPasswordLoginRequestDTO requestDTO);

    Response<AuthTokenResponseDTO> refresh(AuthRefreshRequestDTO requestDTO);

    Response<Void> changePassword(AuthChangePasswordRequestDTO requestDTO);

    Response<Void> grantAdmin(AuthGrantAdminRequestDTO requestDTO);

    Response<Void> revokeAdmin(AuthGrantAdminRequestDTO requestDTO);

    Response<AuthAdminListResponseDTO> listAdmins();

    Response<Void> logout();

    Response<AuthMeResponseDTO> me();
}
