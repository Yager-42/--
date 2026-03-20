package cn.nexus.trigger.http.auth;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import cn.nexus.api.auth.IAuthApi;
import cn.nexus.api.auth.dto.AuthAdminDTO;
import cn.nexus.api.auth.dto.AuthAdminListResponseDTO;
import cn.nexus.api.auth.dto.AuthChangePasswordRequestDTO;
import cn.nexus.api.auth.dto.AuthGrantAdminRequestDTO;
import cn.nexus.api.auth.dto.AuthMeResponseDTO;
import cn.nexus.api.auth.dto.AuthPasswordLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterRequestDTO;
import cn.nexus.api.auth.dto.AuthRegisterResponseDTO;
import cn.nexus.api.auth.dto.AuthSmsLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthSmsSendRequestDTO;
import cn.nexus.api.auth.dto.AuthSmsSendResponseDTO;
import cn.nexus.api.auth.dto.AuthTokenResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.auth.model.valobj.AuthAdminVO;
import cn.nexus.domain.auth.model.valobj.AuthLoginResultVO;
import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;
import cn.nexus.domain.auth.service.AuthService;
import cn.nexus.trigger.http.support.UserContext;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 正式认证 HTTP 入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements IAuthApi {

    private static final String TOKEN_NAME = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer";
    private static final int SMS_EXPIRE_SECONDS = 300;

    private final AuthService authService;

    @PostMapping("/sms/send")
    @Override
    public Response<AuthSmsSendResponseDTO> sendSms(@RequestBody AuthSmsSendRequestDTO requestDTO) {
        try {
            authService.sendSms(
                    requestDTO == null ? null : requestDTO.getPhone(),
                    parseBizType(requestDTO == null ? null : requestDTO.getBizType()),
                    resolveRequestIp()
            );
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    AuthSmsSendResponseDTO.builder().expireSeconds(SMS_EXPIRE_SECONDS).build()
            );
        } catch (AppException e) {
            return Response.<AuthSmsSendResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth send sms failed, req={}", requestDTO, e);
            return Response.<AuthSmsSendResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/register")
    @Override
    public Response<AuthRegisterResponseDTO> register(@RequestBody AuthRegisterRequestDTO requestDTO) {
        try {
            Long userId = authService.register(
                    requestDTO == null ? null : requestDTO.getPhone(),
                    requestDTO == null ? null : requestDTO.getSmsCode(),
                    requestDTO == null ? null : requestDTO.getPassword(),
                    requestDTO == null ? null : requestDTO.getNickname(),
                    requestDTO == null ? null : requestDTO.getAvatarUrl()
            );
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    AuthRegisterResponseDTO.builder().userId(userId).build()
            );
        } catch (AppException e) {
            return Response.<AuthRegisterResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth register failed, req={}", requestDTO, e);
            return Response.<AuthRegisterResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/login/password")
    @Override
    public Response<AuthTokenResponseDTO> passwordLogin(@RequestBody AuthPasswordLoginRequestDTO requestDTO) {
        try {
            AuthLoginResultVO loginResult = authService.passwordLogin(
                    requestDTO == null ? null : requestDTO.getPhone(),
                    requestDTO == null ? null : requestDTO.getPassword()
            );
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    buildTokenResponse(loginResult)
            );
        } catch (AppException e) {
            return Response.<AuthTokenResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth password login failed, req={}", requestDTO, e);
            return Response.<AuthTokenResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/login/sms")
    @Override
    public Response<AuthTokenResponseDTO> smsLogin(@RequestBody AuthSmsLoginRequestDTO requestDTO) {
        try {
            AuthLoginResultVO loginResult = authService.smsLogin(
                    requestDTO == null ? null : requestDTO.getPhone(),
                    requestDTO == null ? null : requestDTO.getSmsCode()
            );
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    buildTokenResponse(loginResult)
            );
        } catch (AppException e) {
            return Response.<AuthTokenResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth sms login failed, req={}", requestDTO, e);
            return Response.<AuthTokenResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/password/change")
    @Override
    public Response<Void> changePassword(@RequestBody AuthChangePasswordRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            Long changedUserId = authService.changePassword(
                    userId,
                    requestDTO == null ? null : requestDTO.getOldPassword(),
                    requestDTO == null ? null : requestDTO.getNewPassword()
            );
            StpUtil.logout(changedUserId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.<Void>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth change password failed, req={}", requestDTO, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/admin/grant")
    @SaCheckRole("ADMIN")
    @Override
    public Response<Void> grantAdmin(@RequestBody AuthGrantAdminRequestDTO requestDTO) {
        try {
            authService.grantAdmin(requestDTO == null ? null : requestDTO.getUserId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.<Void>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth grant admin failed, req={}", requestDTO, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/admin/revoke")
    @SaCheckRole("ADMIN")
    @Override
    public Response<Void> revokeAdmin(@RequestBody AuthGrantAdminRequestDTO requestDTO) {
        try {
            authService.revokeAdmin(requestDTO == null ? null : requestDTO.getUserId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.<Void>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth revoke admin failed, req={}", requestDTO, e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/admins")
    @SaCheckRole("ADMIN")
    @Override
    public Response<AuthAdminListResponseDTO> listAdmins() {
        try {
            java.util.List<AuthAdminVO> admins = authService.listAdmins();
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    AuthAdminListResponseDTO.builder()
                            .userIds(admins.stream().map(AuthAdminVO::getUserId).toList())
                            .admins(admins.stream().map(this::toAdminDto).toList())
                            .build()
            );
        } catch (AppException e) {
            return Response.<AuthAdminListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth list admins failed", e);
            return Response.<AuthAdminListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/logout")
    @Override
    public Response<Void> logout() {
        try {
            StpUtil.logout();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.<Void>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth logout failed", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/me")
    @Override
    public Response<AuthMeResponseDTO> me() {
        try {
            AuthMeVO me = authService.me(UserContext.requireUserId());
            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    AuthMeResponseDTO.builder()
                            .userId(me.getUserId())
                            .phone(me.getPhone())
                            .status(me.getStatus())
                            .nickname(me.getNickname())
                            .avatarUrl(me.getAvatarUrl())
                            .build()
            );
        } catch (AppException e) {
            return Response.<AuthMeResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth me failed", e);
            return Response.<AuthMeResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private AuthTokenResponseDTO buildTokenResponse(AuthLoginResultVO loginResult) {
        StpUtil.login(loginResult.getUserId());
        return AuthTokenResponseDTO.builder()
                .userId(loginResult.getUserId())
                .tokenName(TOKEN_NAME)
                .tokenPrefix(TOKEN_PREFIX)
                .token(StpUtil.getTokenValue())
                .build();
    }

    private AuthAdminDTO toAdminDto(AuthAdminVO admin) {
        return AuthAdminDTO.builder()
                .userId(admin.getUserId())
                .phone(admin.getPhone())
                .status(admin.getStatus())
                .nickname(admin.getNickname())
                .avatarUrl(admin.getAvatarUrl())
                .build();
    }

    private AuthSmsBizTypeVO parseBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "bizType 不能为空");
        }
        try {
            return AuthSmsBizTypeVO.valueOf(bizType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "bizType 仅支持 REGISTER/LOGIN");
        }
    }

    private String resolveRequestIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            if (request != null && request.getRemoteAddr() != null && !request.getRemoteAddr().isBlank()) {
                return request.getRemoteAddr();
            }
        }
        return "unknown";
    }
}
