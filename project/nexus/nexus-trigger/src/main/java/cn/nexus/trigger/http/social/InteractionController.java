package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IInteractionApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.*;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.trigger.mq.producer.LikeSyncProducer;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import cn.nexus.types.event.interaction.LikeFlushTaskEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Resource
    private LikeSyncProducer likeSyncProducer;

    /**
     * 点赞窗口长度（秒），默认 60s。
     */
    @Value("${interaction.like.windowSeconds:60}")
    private long windowSeconds;

    /**
     * 点赞延迟缓冲（秒），默认 10s。
     */
    @Value("${interaction.like.delayBufferSeconds:10}")
    private long delayBufferSeconds;

    @PostMapping("/interact/reaction")
    @Override
    public Response<ReactionResponseDTO> react(@RequestBody ReactionRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        ReactionResultVO vo = interactionService.react(userId, requestDTO.getTargetId(), requestDTO.getTargetType(), requestDTO.getType(), requestDTO.getAction());
        if (vo.isSuccess() && vo.isNeedSchedule()) {
            LikeFlushTaskEvent event = new LikeFlushTaskEvent();
            String targetType = requestDTO.getTargetType() == null ? null : requestDTO.getTargetType().trim().toUpperCase();
            event.setTargetType(targetType);
            event.setTargetId(requestDTO.getTargetId());
            likeSyncProducer.sendDelay(event, delayMs());
        }
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                ReactionResponseDTO.builder().currentCount(vo.getCurrentCount()).success(vo.isSuccess()).build());
    }

    @GetMapping("/interact/reaction/state")
    @Override
    public Response<ReactionStateResponseDTO> reactionState(ReactionStateRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        ReactionStateVO vo = interactionService.reactionState(userId, requestDTO.getTargetId(), requestDTO.getTargetType());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                ReactionStateResponseDTO.builder().likeCount(vo.getLikeCount()).likedByMe(vo.isLikedByMe()).build());
    }

    @PostMapping("/interact/reaction/batchState")
    @Override
    public Response<ReactionBatchStateResponseDTO> batchState(@RequestBody ReactionBatchStateRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        java.util.List<ReactionTargetVO> targets = requestDTO == null || requestDTO.getTargets() == null
                ? java.util.List.of()
                : requestDTO.getTargets().stream()
                .map(t -> ReactionTargetVO.builder()
                        .targetId(t == null ? null : t.getTargetId())
                        .targetType(t == null ? null : t.getTargetType())
                        .build())
                .collect(java.util.stream.Collectors.toList());
        ReactionBatchStateVO vo = interactionService.batchState(userId, targets);
        ReactionBatchStateResponseDTO dto = ReactionBatchStateResponseDTO.builder()
                .items(vo == null || vo.getItems() == null ? java.util.List.of()
                        : vo.getItems().stream().map(this::toReactionStateItem).collect(Collectors.toList()))
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PostMapping("/interact/comment")
    @Override
    public Response<CommentResponseDTO> comment(@RequestBody CommentRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        CommentResultVO vo = interactionService.comment(userId, requestDTO.getPostId(), requestDTO.getParentId(), requestDTO.getContent(), requestDTO.getMentions());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                CommentResponseDTO.builder().commentId(vo.getCommentId()).createTime(vo.getCreateTime()).build());
    }

    @PostMapping("/interact/comment/pin")
    @Override
    public Response<OperationResultDTO> pinComment(@RequestBody PinCommentRequestDTO requestDTO) {
        OperationResultVO vo = interactionService.pinComment(requestDTO.getCommentId(), requestDTO.getPostId());
        return toOperationResult(vo);
    }

    @GetMapping("/notification/list")
    @Override
    public Response<NotificationListResponseDTO> notifications(NotificationListRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        NotificationListVO vo = interactionService.notifications(userId, requestDTO.getCursor());
        NotificationListResponseDTO dto = NotificationListResponseDTO.builder()
                .notifications(vo.getNotifications().stream().map(this::toNotification).collect(Collectors.toList()))
                .nextCursor(vo.getNextCursor())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
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
                .build();
    }

    private ReactionStateItemDTO toReactionStateItem(ReactionStateItemVO vo) {
        if (vo == null) {
            return null;
        }
        return ReactionStateItemDTO.builder()
                .targetId(vo.getTargetId())
                .targetType(vo.getTargetType())
                .likeCount(vo.getLikeCount())
                .likedByMe(vo.isLikedByMe())
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

    private long delayMs() {
        long window = Math.max(0L, windowSeconds);
        long buffer = Math.max(0L, delayBufferSeconds);
        return (window + buffer) * 1000L;
    }
}
