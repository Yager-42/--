package cn.nexus.trigger.http.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.IUserSettingApi;
import cn.nexus.api.user.dto.UserPrivacyResponseDTO;
import cn.nexus.api.user.dto.UserPrivacyUpdateRequestDTO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.adapter.repository.IUserPrivacyRepository;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
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
 * 用户隐私设置接口入口。
 *
 * <p>控制器只负责两件事：确认当前请求归属哪个用户，以及把 HTTP DTO 转成领域服务能直接消费的参数。
 * 隐私真值仍然只放在 {@code user_privacy_setting}。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class UserSettingController implements IUserSettingApi {

    @Resource
    private UserService userService;

    @Resource
    private IUserProfileRepository userProfileRepository;

    @Resource
    private IUserPrivacyRepository userPrivacyRepository;

    /**
     * 查询当前登录用户的隐私设置。
     *
     * @return 隐私设置结果，类型：{@link Response}&lt;{@link UserPrivacyResponseDTO}&gt;
     */
    @GetMapping("/user/me/privacy")
    @Override
    public Response<UserPrivacyResponseDTO> myPrivacy() {
        try {
            Long userId = UserContext.requireUserId();
            UserProfileVO profile = userProfileRepository.get(userId);
            if (profile == null) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
            Boolean needApproval = userPrivacyRepository.getNeedApproval(userId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    UserPrivacyResponseDTO.builder().needApproval(Boolean.TRUE.equals(needApproval)).build());
        } catch (AppException e) {
            return Response.<UserPrivacyResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("get my privacy api failed", e);
            return Response.<UserPrivacyResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 更新当前登录用户的隐私设置。
     *
     * @param requestDTO 隐私设置更新请求，类型：{@link UserPrivacyUpdateRequestDTO}
     * @return 更新结果，类型：{@link Response}&lt;{@link OperationResultDTO}&gt;
     */
    @PostMapping("/user/me/privacy")
    @Override
    public Response<OperationResultDTO> updateMyPrivacy(@RequestBody UserPrivacyUpdateRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            Boolean needApproval = requestDTO == null ? null : requestDTO.getNeedApproval();
            OperationResultVO vo = userService.updateMyPrivacy(userId, needApproval);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResult(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("update my privacy api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
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
