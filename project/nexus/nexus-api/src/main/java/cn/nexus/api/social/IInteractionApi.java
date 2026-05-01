package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.*;

/**
 * 互动与通知接口定义。
 */
public interface IInteractionApi {

    Response<CommentResponseDTO> comment(CommentRequestDTO requestDTO);

    Response<OperationResultDTO> pinComment(PinCommentRequestDTO requestDTO);

    Response<NotificationListResponseDTO> notifications(NotificationListRequestDTO requestDTO);

    Response<OperationResultDTO> readNotification(NotificationReadRequestDTO requestDTO);

    Response<OperationResultDTO> readAllNotifications();

}
