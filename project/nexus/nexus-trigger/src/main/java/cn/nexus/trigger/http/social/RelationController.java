package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRelationApi;
import cn.nexus.api.social.relation.dto.*;
import cn.nexus.domain.social.model.valobj.FollowResultVO;
import cn.nexus.domain.social.model.valobj.FriendDecisionResultVO;
import cn.nexus.domain.social.model.valobj.FriendRequestResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IRelationService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 关系接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/relation")
public class RelationController implements IRelationApi {

    @Resource
    private IRelationService relationService;

    @PostMapping("/follow")
    @Override
    public Response<FollowResponseDTO> follow(@RequestBody FollowRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            FollowResultVO vo = relationService.follow(userId, requestDTO.getTargetId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    FollowResponseDTO.builder().status(vo.getStatus()).build());
        } catch (AppException e) {
            return Response.<FollowResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation follow api failed, req={}", requestDTO, e);
            return Response.<FollowResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/unfollow")
    @Override
    public Response<FollowResponseDTO> unfollow(@RequestBody FollowRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            FollowResultVO vo = relationService.unfollow(userId, requestDTO.getTargetId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    FollowResponseDTO.builder().status(vo.getStatus()).build());
        } catch (AppException e) {
            return Response.<FollowResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation unfollow api failed, req={}", requestDTO, e);
            return Response.<FollowResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/friend/request")
    @Override
    public Response<FriendRequestResponseDTO> friendRequest(@RequestBody FriendRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            FriendRequestResultVO vo = relationService.friendRequest(
                    userId, requestDTO.getTargetId(),
                    requestDTO.getVerifyMsg(), requestDTO.getSourceChannel());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    FriendRequestResponseDTO.builder().requestId(vo.getRequestId()).status(vo.getStatus()).build());
        } catch (AppException e) {
            return Response.<FriendRequestResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation friend request api failed, req={}", requestDTO, e);
            return Response.<FriendRequestResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/friend/decision")
    @Override
    public Response<FriendDecisionResponseDTO> friendDecision(@RequestBody FriendDecisionRequestDTO requestDTO) {
        try {
            FriendDecisionResultVO vo = relationService.friendDecision(requestDTO.getRequestIds(), requestDTO.getAction());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    FriendDecisionResponseDTO.builder().success(vo.isSuccess()).build());
        } catch (AppException e) {
            return Response.<FriendDecisionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation friend decision api failed, req={}", requestDTO, e);
            return Response.<FriendDecisionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/block")
    @Override
    public Response<BlockResponseDTO> block(@RequestBody BlockRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = relationService.block(userId, requestDTO.getTargetId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    BlockResponseDTO.builder().success(vo.isSuccess()).build());
        } catch (AppException e) {
            return Response.<BlockResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation block api failed, req={}", requestDTO, e);
            return Response.<BlockResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
