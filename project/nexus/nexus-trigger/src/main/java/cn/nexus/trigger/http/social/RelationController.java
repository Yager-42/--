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
        FollowResultVO vo = relationService.follow(requestDTO.getSourceId(), requestDTO.getTargetId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FollowResponseDTO.builder().status(vo.getStatus()).build());
    }

    @PostMapping("/friend/request")
    @Override
    public Response<FriendRequestResponseDTO> friendRequest(@RequestBody FriendRequestDTO requestDTO) {
        FriendRequestResultVO vo = relationService.friendRequest(
                requestDTO.getSourceId(), requestDTO.getTargetId(),
                requestDTO.getVerifyMsg(), requestDTO.getSourceChannel());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FriendRequestResponseDTO.builder().requestId(vo.getRequestId()).status(vo.getStatus()).build());
    }

    @PostMapping("/friend/decision")
    @Override
    public Response<FriendDecisionResponseDTO> friendDecision(@RequestBody FriendDecisionRequestDTO requestDTO) {
        FriendDecisionResultVO vo = relationService.friendDecision(requestDTO.getRequestId(), requestDTO.getAction());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                FriendDecisionResponseDTO.builder().success(vo.isSuccess()).build());
    }

    @PostMapping("/block")
    @Override
    public Response<BlockResponseDTO> block(@RequestBody BlockRequestDTO requestDTO) {
        OperationResultVO vo = relationService.block(requestDTO.getSourceId(), requestDTO.getTargetId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                BlockResponseDTO.builder().success(vo.isSuccess()).build());
    }

    @PostMapping("/list")
    @Override
    public Response<RelationGroupResponseDTO> manageGroup(@RequestBody RelationGroupRequestDTO requestDTO) {
        RelationGroupVO vo = relationService.manageGroup(
                requestDTO.getUserId(),
                requestDTO.getAction(),
                requestDTO.getListName(),
                requestDTO.getListId(),
                requestDTO.getMemberIds());
        RelationGroupResponseDTO dto = RelationGroupResponseDTO.builder()
                .listId(vo.getListId())
                .listName(vo.getListName())
                .memberIds(vo.getMemberIds())
                .build();
        log.debug("分组操作完成: {}", dto);
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
