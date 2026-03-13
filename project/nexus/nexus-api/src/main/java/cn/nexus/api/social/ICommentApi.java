package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.CommentHotRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentHotResponseDTO;
import cn.nexus.api.social.interaction.dto.CommentListRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentListResponseDTO;
import cn.nexus.api.social.interaction.dto.CommentReplyListRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentReplyListResponseDTO;

/**
 * 评论读/删相关接口定义。
 */
public interface ICommentApi {

    Response<CommentListResponseDTO> list(CommentListRequestDTO requestDTO);

    Response<CommentReplyListResponseDTO> replyList(CommentReplyListRequestDTO requestDTO);

    Response<CommentHotResponseDTO> hot(CommentHotRequestDTO requestDTO);

    Response<OperationResultDTO> delete(Long commentId);
}

