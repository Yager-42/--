package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRelationApi;
import cn.nexus.api.social.relation.dto.BlockRequestDTO;
import cn.nexus.api.social.relation.dto.BlockResponseDTO;
import cn.nexus.api.social.relation.dto.FollowRequestDTO;
import cn.nexus.api.social.relation.dto.FollowResponseDTO;
import cn.nexus.api.social.relation.dto.RelationCounterResponseDTO;
import cn.nexus.api.social.relation.dto.RelationListRequestDTO;
import cn.nexus.api.social.relation.dto.RelationListResponseDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchRequestDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchResponseDTO;
import cn.nexus.api.social.relation.dto.RelationUserDTO;
import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;
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
 * 社交关系 HTTP 入口：统一接住关注、取关、拉黑和关系查询请求。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
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

    @Resource
    private IUserCounterService userCounterService;

    /**
     * 发起关注请求。
     *
     * @param requestDTO 关注请求体，类型：{@link FollowRequestDTO}
     * @return 关注结果，类型：{@link Response}
     */
    @PostMapping("/follow")
    @Override
    public Response<FollowResponseDTO> follow(@RequestBody FollowRequestDTO requestDTO) {
        try {
            // sourceId 一律从登录态读取，避免前端伪造“替别人关注”。
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

    /**
     * 发起取关请求。
     *
     * @param requestDTO 取关请求体，类型：{@link FollowRequestDTO}
     * @return 取关结果，类型：{@link Response}
     */
    @PostMapping("/unfollow")
    @Override
    public Response<FollowResponseDTO> unfollow(@RequestBody FollowRequestDTO requestDTO) {
        try {
            // 取关和关注一样，只认当前登录用户，不信请求体里的身份信息。
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

    /**
     * 发起拉黑请求。
     *
     * @param requestDTO 拉黑请求体，类型：{@link BlockRequestDTO}
     * @return 拉黑结果，类型：{@link Response}
     */
    @PostMapping("/block")
    @Override
    public Response<BlockResponseDTO> block(@RequestBody BlockRequestDTO requestDTO) {
        try {
            // 拉黑也是强身份操作，必须绑定当前登录用户。
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

    /**
     * 查询当前用户的关系计数。
     *
     * @return 关系计数响应，类型：{@link Response}
     */
    @GetMapping("/counter")
    @Override
    public Response<RelationCounterResponseDTO> counter() {
        try {
            Long userId = UserContext.requireUserId();
            UserRelationCounterVO vo = userCounterService.readRelationCountersWithVerification(userId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toCounterDTO(vo));
        } catch (AppException e) {
            return Response.<RelationCounterResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("relation counter api failed", e);
            return Response.<RelationCounterResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 查询关注列表。
     *
     * @param requestDTO 列表查询参数，类型：{@link RelationListRequestDTO}
     * @return 关注列表响应，类型：{@link Response}
     */
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

    /**
     * 查询粉丝列表。
     *
     * @param requestDTO 列表查询参数，类型：{@link RelationListRequestDTO}
     * @return 粉丝列表响应，类型：{@link Response}
     */
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

    /**
     * 批量查询目标用户的关注态和拉黑态。
     *
     * @param requestDTO 批量查询请求体，类型：{@link RelationStateBatchRequestDTO}
     * @return 批量关系状态响应，类型：{@link Response}
     */
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

    private RelationCounterResponseDTO toCounterDTO(UserRelationCounterVO vo) {
        if (vo == null) {
            return RelationCounterResponseDTO.builder().build();
        }
        return RelationCounterResponseDTO.builder()
                .followings(vo.getFollowings())
                .followers(vo.getFollowers())
                .posts(vo.getPosts())
                .likedPosts(vo.getLikedPosts())
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
