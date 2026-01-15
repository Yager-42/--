package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IInteractionApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.*;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.types.enums.ResponseCode;
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
        Long userId = UserContext.requireUserId();
        ReactionResultVO vo = interactionService.react(userId, requestDTO.getTargetId(), requestDTO.getTargetType(), requestDTO.getType(), requestDTO.getAction());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                ReactionResponseDTO.builder().currentCount(vo.getCurrentCount()).success(vo.isSuccess()).build());
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
