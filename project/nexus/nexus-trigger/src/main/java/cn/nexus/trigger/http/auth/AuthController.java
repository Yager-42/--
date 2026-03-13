package cn.nexus.trigger.http.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.nexus.api.auth.IAuthApi;
import cn.nexus.api.auth.dto.AuthLoginRequestDTO;
import cn.nexus.api.auth.dto.AuthLoginResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal dev login.
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements IAuthApi {

    private static final String TOKEN_NAME = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer";

    private final IUserBaseDao userBaseDao;
    private final ISocialIdPort socialIdPort;

    @PostMapping("/login")
    @Override
    public Response<AuthLoginResponseDTO> login(@RequestBody AuthLoginRequestDTO requestDTO) {
        try {
            Long userId = requestDTO == null ? null : requestDTO.getUserId();
            String username = trimToNull(requestDTO == null ? null : requestDTO.getUsername());
            String nickname = trimToNull(requestDTO == null ? null : requestDTO.getNickname());
            String avatarUrl = requestDTO == null ? null : requestDTO.getAvatarUrl();

            if (userId == null && username == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId/username 不能为空");
            }

            UserBasePO existed = null;
            if (userId != null) {
                existed = userBaseDao.selectByUserId(userId);
            }
            if (existed == null && username != null) {
                existed = userBaseDao.selectByUsername(username);
            }

            Long finalUserId;
            if (existed != null) {
                finalUserId = existed.getUserId();
                if (userId != null && !userId.equals(finalUserId)) {
                    throw new AppException(ResponseCode.CONFLICT.getCode(), "userId/username 冲突");
                }
                if (username != null && existed.getUsername() != null && !username.equals(existed.getUsername())) {
                    throw new AppException(ResponseCode.CONFLICT.getCode(), "userId/username 冲突");
                }
            } else {
                finalUserId = userId == null ? socialIdPort.nextId() : userId;
                String finalUsername = username == null ? ("u" + finalUserId) : username;
                String finalNickname = nickname == null ? finalUsername : nickname;
                String finalAvatar = avatarUrl == null ? "" : avatarUrl;

                UserBasePO po = new UserBasePO();
                po.setUserId(finalUserId);
                po.setUsername(finalUsername);
                po.setNickname(finalNickname);
                po.setAvatarUrl(finalAvatar);

                try {
                    userBaseDao.insert(po);
                } catch (Exception e) {
                    UserBasePO retry = userBaseDao.selectByUsername(finalUsername);
                    if (retry == null || retry.getUserId() == null) {
                        throw e;
                    }
                    finalUserId = retry.getUserId();
                }
            }

            StpUtil.login(finalUserId);
            String token = StpUtil.getTokenValue();

            return Response.success(
                    ResponseCode.SUCCESS.getCode(),
                    ResponseCode.SUCCESS.getInfo(),
                    AuthLoginResponseDTO.builder()
                            .userId(finalUserId)
                            .tokenName(TOKEN_NAME)
                            .tokenPrefix(TOKEN_PREFIX)
                            .token(token)
                            .build()
            );
        } catch (AppException e) {
            return Response.<AuthLoginResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("auth login failed, req={}", requestDTO, e);
            return Response.<AuthLoginResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
