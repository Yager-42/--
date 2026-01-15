package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.*;

/**
 * 互动与通知接口定义。
 */
public interface IInteractionApi {

    Response<ReactionResponseDTO> react(ReactionRequestDTO requestDTO);

    Response<ReactionStateResponseDTO> reactionState(ReactionStateRequestDTO requestDTO);

    Response<ReactionBatchStateResponseDTO> batchState(ReactionBatchStateRequestDTO requestDTO);

    Response<CommentResponseDTO> comment(CommentRequestDTO requestDTO);

    Response<OperationResultDTO> pinComment(PinCommentRequestDTO requestDTO);

    Response<NotificationListResponseDTO> notifications(NotificationListRequestDTO requestDTO);

    Response<TipResponseDTO> tip(TipRequestDTO requestDTO);

    Response<PollCreateResponseDTO> createPoll(PollCreateRequestDTO requestDTO);

    Response<PollVoteResponseDTO> vote(PollVoteRequestDTO requestDTO);

    Response<WalletBalanceResponseDTO> balance(WalletBalanceRequestDTO requestDTO);
}
