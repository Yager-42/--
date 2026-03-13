package cn.nexus.trigger.http.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.IUserProfileApi;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;
import cn.nexus.api.user.dto.UserProfileResponseDTO;
import cn.nexus.api.user.dto.UserProfileUpdateRequestDTO;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.model.valobj.UserProfilePatchVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 Profile 接口入口。
 *
 * <p>这里同时承载“看自己”“看别人”和“改自己”三条链路，但控制器本身只做参数接收、异常转码和 DTO/VO 转换，
 * 真正规则仍然收口在领域服务与仓储里。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class UserProfileController implements IUserProfileApi {

    @Resource
    private UserService userService;

    @Resource
    private IUserProfileRepository userProfileRepository;

    @Resource
    private IUserStatusRepository userStatusRepository;

    @Resource
    private IRelationPolicyPort relationPolicyPort;

    /**
     * 查询当前登录用户的资料。
     *
     * @return 当前用户资料，类型：{@link Response}&lt;{@link UserProfileResponseDTO}&gt;
     */
    @GetMapping("/user/me/profile")
    @Override
    public Response<UserProfileResponseDTO> myProfile() {
        try {
            Long userId = UserContext.requireUserId();
            UserProfileVO profile = userProfileRepository.get(userId);
            if (profile == null) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
            String status = userStatusRepository.getStatus(userId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toProfile(profile, status));
        } catch (AppException e) {
            return Response.<UserProfileResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("my profile api failed", e);
            return Response.<UserProfileResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 查询目标用户的公开资料。
     *
     * @param requestDTO 查询请求，包含目标用户 ID，类型：{@link UserProfileQueryRequestDTO}
     * @return 目标用户资料，类型：{@link Response}&lt;{@link UserProfileResponseDTO}&gt;
     */
    @GetMapping("/user/profile")
    @Override
    public Response<UserProfileResponseDTO> profile(UserProfileQueryRequestDTO requestDTO) {
        try {
            Long viewerId = UserContext.requireUserId();
            Long targetUserId = requestDTO == null ? null : requestDTO.getTargetUserId();
            if (targetUserId == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "targetUserId 不能为空");
            }
            // 最小隐私策略：任一方向屏蔽都返回 NOT_FOUND，不泄露“这个人其实存在”。
            if (relationPolicyPort.isBlocked(viewerId, targetUserId) || relationPolicyPort.isBlocked(targetUserId, viewerId)) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }

            UserProfileVO profile = userProfileRepository.get(targetUserId);
            if (profile == null) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
            String status = userStatusRepository.getStatus(targetUserId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toProfile(profile, status));
        } catch (AppException e) {
            return Response.<UserProfileResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("user profile api failed, req={}", requestDTO, e);
            return Response.<UserProfileResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 更新当前登录用户的资料。
     *
     * @param requestDTO 资料更新请求，类型：{@link UserProfileUpdateRequestDTO}
     * @return 更新结果，类型：{@link Response}&lt;{@link OperationResultDTO}&gt;
     */
    @PostMapping("/user/me/profile")
    @Override
    public Response<OperationResultDTO> updateMyProfile(@RequestBody UserProfileUpdateRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            // 控制器只负责把 HTTP DTO 压成领域 Patch，不在这里做业务判断。
            UserProfilePatchVO patch = requestDTO == null ? null : UserProfilePatchVO.builder()
                    .nickname(requestDTO.getNickname())
                    .avatarUrl(requestDTO.getAvatarUrl())
                    .build();
            OperationResultVO vo = userService.updateMyProfile(userId, patch);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResult(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("update my profile api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private UserProfileResponseDTO toProfile(UserProfileVO profile, String status) {
        return UserProfileResponseDTO.builder()
                .userId(profile.getUserId())
                .username(profile.getUsername())
                .nickname(profile.getNickname())
                .avatarUrl(profile.getAvatarUrl())
                .status(status)
                .build();
    }

    private OperationResultDTO toOperationResult(OperationResultVO vo) {
        if (vo == null) {
            return OperationResultDTO.builder().success(false).status("NULL").message("null result").build();
        }
        return OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
    }
}
