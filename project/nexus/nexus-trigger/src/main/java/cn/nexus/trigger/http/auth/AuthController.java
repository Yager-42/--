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
 * 开发态登录入口：负责补齐最小用户名片并建立登录态。
 *
 * <p>这条链路只做两件事：先按 {@code userId}/{@code username} 对 {@code user_base}
 * 做显式查重，再在需要时补一条最小档案。它不是正式账号中心，只是让后续用户域链路能跑起来。</p>
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-03
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements IAuthApi {

    /**
     * 返回给客户端的 Token 名称。
     */
    private static final String TOKEN_NAME = "Authorization";

    /**
     * 返回给客户端的 Token 前缀。
     */
    private static final String TOKEN_PREFIX = "Bearer";

    private final IUserBaseDao userBaseDao;
    private final ISocialIdPort socialIdPort;

    /**
     * 登录并在必要时初始化最小用户名片。
     *
     * @param requestDTO 登录请求，允许只传 {@code userId} 或只传 {@code username}，类型：{@link AuthLoginRequestDTO}
     * @return 登录结果，成功时返回 Token 和最终用户 ID，类型：{@link Response}&lt;{@link AuthLoginResponseDTO}&gt;
     */
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
            // 先按主键查，再按唯一用户名查，避免重复建档。
            if (userId != null) {
                existed = userBaseDao.selectByUserId(userId);
            }
            if (existed == null && username != null) {
                existed = userBaseDao.selectByUsername(username);
            }

            Long finalUserId;
            if (existed != null) {
                finalUserId = existed.getUserId();
                // 只要传入的 userId/username 和库里真值对不上，就直接报冲突，不在这里打补丁兜底。
                if (userId != null && !userId.equals(finalUserId)) {
                    throw new AppException(ResponseCode.CONFLICT.getCode(), "userId/username 冲突");
                }
                if (username != null && existed.getUsername() != null && !username.equals(existed.getUsername())) {
                    throw new AppException(ResponseCode.CONFLICT.getCode(), "userId/username 冲突");
                }
            } else {
                // 只有完全查不到老记录时才补最小档案，避免把“已有用户”误插成新用户。
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
                    // 并发插入时再按 username 回查一次；能查到就复用结果，查不到才把异常继续抛出去。
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
