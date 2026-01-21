package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IInteractionApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.*;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * 互动接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class InteractionController implements IInteractionApi {

    @Resource
    private IInteractionService interactionService;

    @PostMapping("/interact/reaction")
    @Override
    public Response<ReactionResponseDTO> react(@RequestBody ReactionRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            ReactionResultVO vo = interactionService.react(
                    userId,
                    requestDTO.getTargetId(),
                    requestDTO.getTargetType(),
                    requestDTO.getType(),
                    requestDTO.getAction(),
                    requestDTO.getRequestId()
            );
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    ReactionResponseDTO.builder()
                            .requestId(vo.getRequestId())
                            .currentCount(vo.getCurrentCount())
                            .success(vo.isSuccess())
                            .build());
        } catch (AppException e) {
            return Response.<ReactionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("reaction api failed, req={}", requestDTO, e);
            return Response.<ReactionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/interact/reaction/state")
    @Override
    public Response<ReactionStateResponseDTO> reactionState(ReactionStateRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            ReactionStateVO vo = interactionService.reactionState(
                    userId,
                    requestDTO.getTargetId(),
                    requestDTO.getTargetType(),
                    requestDTO.getType()
            );
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    ReactionStateResponseDTO.builder().state(vo.isState()).currentCount(vo.getCurrentCount()).build());
        } catch (AppException e) {
            return Response.<ReactionStateResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("reaction state api failed, req={}", requestDTO, e);
            return Response.<ReactionStateResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/interact/comment")
    @Override
    public Response<CommentResponseDTO> comment(@RequestBody CommentRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            // @提及由后端统一从 content 解析 @username；不接收客户端传入的 mentions(userId 列表)。
            CommentResultVO vo = interactionService.comment(userId, requestDTO.getPostId(), requestDTO.getParentId(), requestDTO.getContent());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    CommentResponseDTO.builder().commentId(vo.getCommentId()).createTime(vo.getCreateTime()).build());
        } catch (AppException e) {
            return Response.<CommentResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("comment api failed, req={}", requestDTO, e);
            return Response.<CommentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/interact/comment/pin")
    @Override
    public Response<OperationResultDTO> pinComment(@RequestBody PinCommentRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = interactionService.pinComment(userId, requestDTO.getCommentId(), requestDTO.getPostId());
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("pin comment api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/notification/list")
    @Override
    public Response<NotificationListResponseDTO> notifications(NotificationListRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            NotificationListVO vo = interactionService.notifications(userId, requestDTO.getCursor());
            NotificationListResponseDTO dto = NotificationListResponseDTO.builder()
                    .notifications(vo.getNotifications().stream().map(this::toNotification).collect(Collectors.toList()))
                    .nextCursor(vo.getNextCursor())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<NotificationListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("notification list api failed, req={}", requestDTO, e);
            return Response.<NotificationListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/notification/read")
    @Override
    public Response<OperationResultDTO> readNotification(@RequestBody NotificationReadRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            Long notificationId = requestDTO == null ? null : requestDTO.getNotificationId();
            OperationResultVO vo = interactionService.readNotification(userId, notificationId);
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("notification read api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/notification/read/all")
    @Override
    public Response<OperationResultDTO> readAllNotifications() {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = interactionService.readAllNotifications(userId);
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("notification read all api failed", e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/wallet/tip")
    @Override
    public Response<TipResponseDTO> tip(@RequestBody TipRequestDTO requestDTO) {
        TipResultVO vo = interactionService.tip(requestDTO.getToUserId(), requestDTO.getAmount(), requestDTO.getCurrency(), requestDTO.getPostId());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                TipResponseDTO.builder().txId(vo.getTxId()).effectUrl(vo.getEffectUrl()).build());
    }

    @PostMapping("/interaction/poll/create")
    @Override
    public Response<PollCreateResponseDTO> createPoll(@RequestBody PollCreateRequestDTO requestDTO) {
        PollCreateResultVO vo = interactionService.createPoll(requestDTO.getQuestion(), requestDTO.getOptions(), requestDTO.getAllowMulti(), requestDTO.getExpireSeconds());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                PollCreateResponseDTO.builder().pollId(vo.getPollId()).build());
    }

    @PostMapping("/interaction/poll/vote")
    @Override
    public Response<PollVoteResponseDTO> vote(@RequestBody PollVoteRequestDTO requestDTO) {
        PollVoteResultVO vo = interactionService.vote(requestDTO.getPollId(), requestDTO.getOptionIds());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                PollVoteResponseDTO.builder().updatedStats(vo.getUpdatedStats()).build());
    }

    @GetMapping("/wallet/balance")
    @Override
    public Response<WalletBalanceResponseDTO> balance(WalletBalanceRequestDTO requestDTO) {
        WalletBalanceVO vo = interactionService.balance(requestDTO.getCurrencyType());
        WalletBalanceResponseDTO dto = WalletBalanceResponseDTO.builder()
                .currencyType(vo.getCurrencyType())
                .amount(vo.getAmount())
                .frozenAmount(vo.getFrozenAmount())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    private NotificationDTO toNotification(NotificationVO vo) {
        return NotificationDTO.builder()
                .title(vo.getTitle())
                .content(vo.getContent())
                .createTime(vo.getCreateTime())
                .notificationId(vo.getNotificationId())
                .bizType(vo.getBizType())
                .targetType(vo.getTargetType())
                .targetId(vo.getTargetId())
                .postId(vo.getPostId())
                .rootCommentId(vo.getRootCommentId())
                .lastCommentId(vo.getLastCommentId())
                .lastActorUserId(vo.getLastActorUserId())
                .unreadCount(vo.getUnreadCount())
                .build();
    }

    private Response<OperationResultDTO> toOperationResult(OperationResultVO vo) {
        OperationResultDTO dto = OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
