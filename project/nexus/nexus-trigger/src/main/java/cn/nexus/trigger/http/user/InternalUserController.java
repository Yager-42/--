package cn.nexus.trigger.http.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.user.IUserInternalUserApi;
import cn.nexus.api.user.dto.UserInternalUpsertRequestDTO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.model.valobj.UserInternalUpsertRequestVO;
import cn.nexus.domain.user.service.UserService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户域 internal 写入口（给系统调用）。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class InternalUserController implements IUserInternalUserApi {

    @Resource
    private UserService userService;

    @PostMapping("/internal/user/upsert")
    @Override
    public Response<OperationResultDTO> upsert(@RequestBody UserInternalUpsertRequestDTO requestDTO) {
        try {
            UserInternalUpsertRequestVO req = requestDTO == null ? null : UserInternalUpsertRequestVO.builder()
                    .userId(requestDTO.getUserId())
                    .username(requestDTO.getUsername())
                    .nickname(requestDTO.getNickname())
                    .avatarUrl(requestDTO.getAvatarUrl())
                    .needApproval(requestDTO.getNeedApproval())
                    .status(requestDTO.getStatus())
                    .build();

            OperationResultVO vo = userService.internalUpsert(req);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResult(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("internal user upsert api failed, req={}", requestDTO, e);
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

