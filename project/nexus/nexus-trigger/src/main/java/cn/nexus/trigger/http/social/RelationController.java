package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRelationApi;
import cn.nexus.api.social.relation.dto.BlockRequestDTO;
import cn.nexus.api.social.relation.dto.BlockResponseDTO;
import cn.nexus.api.social.relation.dto.FollowRequestDTO;
import cn.nexus.api.social.relation.dto.FollowResponseDTO;
import cn.nexus.api.social.relation.dto.RelationListRequestDTO;
import cn.nexus.api.social.relation.dto.RelationListResponseDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchRequestDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchResponseDTO;
import cn.nexus.api.social.relation.dto.RelationUserDTO;
import cn.nexus.domain.social.model.valobj.FollowResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.RelationListVO;
import cn.nexus.domain.social.model.valobj.RelationStateBatchVO;
import cn.nexus.domain.social.model.valobj.RelationUserVO;
import cn.nexus.domain.social.service.IRelationService;
import cn.nexus.domain.social.service.RelationQueryService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Resource
    private RelationQueryService relationQueryService;

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

    @GetMapping("/following")
    @Override
    public Response<RelationListResponseDTO> following(RelationListRequestDTO requestDTO) {
        try {
            RelationListVO vo = relationQueryService.following(requestDTO == null ? null : requestDTO.getUserId(),
                    requestDTO == null ? null : requestDTO.getCursor(),
                    requestDTO == null ? null : requestDTO.getLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toRelationListDTO(vo));
        } catch (AppException e) {
            return Response.<RelationListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation following api failed, req={}", requestDTO, e);
            return Response.<RelationListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/followers")
    @Override
    public Response<RelationListResponseDTO> followers(RelationListRequestDTO requestDTO) {
        try {
            RelationListVO vo = relationQueryService.followers(requestDTO == null ? null : requestDTO.getUserId(),
                    requestDTO == null ? null : requestDTO.getCursor(),
                    requestDTO == null ? null : requestDTO.getLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toRelationListDTO(vo));
        } catch (AppException e) {
            return Response.<RelationListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation followers api failed, req={}", requestDTO, e);
            return Response.<RelationListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/state/batch")
    @Override
    public Response<RelationStateBatchResponseDTO> stateBatch(@RequestBody RelationStateBatchRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            RelationStateBatchVO vo = relationQueryService.batchState(userId, requestDTO == null ? null : requestDTO.getTargetUserIds());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    RelationStateBatchResponseDTO.builder()
                            .followingUserIds(vo.getFollowingUserIds())
                            .blockedUserIds(vo.getBlockedUserIds())
                            .build());
        } catch (AppException e) {
            return Response.<RelationStateBatchResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation state batch api failed, req={}", requestDTO, e);
            return Response.<RelationStateBatchResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private RelationListResponseDTO toRelationListDTO(RelationListVO vo) {
        return RelationListResponseDTO.builder()
                .items(vo.getItems().stream().map(this::toRelationUserDTO).collect(Collectors.toList()))
                .nextCursor(vo.getNextCursor())
                .build();
    }

    private RelationUserDTO toRelationUserDTO(RelationUserVO vo) {
        return RelationUserDTO.builder()
                .userId(vo.getUserId())
                .nickname(vo.getNickname())
                .avatar(vo.getAvatarUrl())
                .followTime(vo.getFollowTime())
                .build();
    }
}
