package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRelationApi;
import cn.nexus.api.social.relation.dto.*;
import cn.nexus.domain.social.model.valobj.FollowResultVO;
import cn.nexus.domain.social.model.valobj.FriendDecisionResultVO;
import cn.nexus.domain.social.model.valobj.FriendRequestResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.RelationGroupVO;
import cn.nexus.domain.social.service.IRelationService;
import cn.nexus.types.enums.ResponseCode;
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
        Long userId = UserContext.requireUserId();
        FollowResultVO vo = relationService.follow(userId, requestDTO.getTargetId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FollowResponseDTO.builder().status(vo.getStatus()).build());
    }

    @PostMapping("/unfollow")
    @Override
    public Response<FollowResponseDTO> unfollow(@RequestBody FollowRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        FollowResultVO vo = relationService.unfollow(userId, requestDTO.getTargetId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FollowResponseDTO.builder().status(vo.getStatus()).build());
    }

    @PostMapping("/friend/request")
    @Override
    public Response<FriendRequestResponseDTO> friendRequest(@RequestBody FriendRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        FriendRequestResultVO vo = relationService.friendRequest(
                userId, requestDTO.getTargetId(),
                requestDTO.getVerifyMsg(), requestDTO.getSourceChannel());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FriendRequestResponseDTO.builder().requestId(vo.getRequestId()).status(vo.getStatus()).build());
    }

    @PostMapping("/friend/decision")
    @Override
    public Response<FriendDecisionResponseDTO> friendDecision(@RequestBody FriendDecisionRequestDTO requestDTO) {
        FriendDecisionResultVO vo = relationService.friendDecision(requestDTO.getRequestIds(), requestDTO.getAction());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FriendDecisionResponseDTO.builder().success(vo.isSuccess()).build());
    }

    @PostMapping("/block")
    @Override
    public Response<BlockResponseDTO> block(@RequestBody BlockRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        OperationResultVO vo = relationService.block(userId, requestDTO.getTargetId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                BlockResponseDTO.builder().success(vo.isSuccess()).build());
    }

    @PostMapping("/list")
    @Override
    public Response<RelationGroupResponseDTO> manageGroup(@RequestBody RelationGroupRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        RelationGroupVO vo = relationService.manageGroup(
                userId,
                requestDTO.getAction(),
                requestDTO.getListName(),
                requestDTO.getListId(),
                requestDTO.getMemberIds(),
                requestDTO.getSourceListId(),
                requestDTO.getTargetListId(),
                requestDTO.getAddMemberIds(),
                requestDTO.getRemoveMemberIds(),
                requestDTO.getIdempotentToken());
        RelationGroupResponseDTO dto = RelationGroupResponseDTO.builder()
                .listId(vo.getListId())
                .listName(vo.getListName())
                .memberIds(vo.getMemberIds())
                .build();
        log.debug("分组操作完成: {}", dto);
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
