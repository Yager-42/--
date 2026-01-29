package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.ICommentApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.interaction.dto.CommentHotRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentHotResponseDTO;
import cn.nexus.api.social.interaction.dto.CommentListRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentListResponseDTO;
import cn.nexus.api.social.interaction.dto.CommentReplyListRequestDTO;
import cn.nexus.api.social.interaction.dto.CommentReplyListResponseDTO;
import cn.nexus.api.social.interaction.dto.CommentViewDTO;
import cn.nexus.api.social.interaction.dto.RootCommentViewDTO;
import cn.nexus.domain.social.model.valobj.CommentHotVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.ReplyCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentViewVO;
import cn.nexus.domain.social.service.ICommentQueryService;
import cn.nexus.domain.social.service.IInteractionService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论读/删接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class CommentController implements ICommentApi {

    @Resource
    private ICommentQueryService commentQueryService;

    @Resource
    private IInteractionService interactionService;

    @GetMapping("/comment/list")
    @Override
    public Response<CommentListResponseDTO> list(CommentListRequestDTO requestDTO) {
        try {
            Long viewerId = UserContext.getUserId();
            RootCommentPageVO vo = commentQueryService.listRootComments(
                    requestDTO.getPostId(),
                    viewerId,
                    requestDTO.getCursor(),
                    requestDTO.getLimit(),
                    requestDTO.getPreloadReplyLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDto(vo));
        } catch (AppException e) {
            return Response.<CommentListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("comment list api failed, req={}", requestDTO, e);
            return Response.<CommentListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/comment/reply/list")
    @Override
    public Response<CommentReplyListResponseDTO> replyList(CommentReplyListRequestDTO requestDTO) {
        try {
            Long viewerId = UserContext.getUserId();
            ReplyCommentPageVO vo = commentQueryService.listReplies(requestDTO.getRootId(), viewerId, requestDTO.getCursor(), requestDTO.getLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDto(vo));
        } catch (AppException e) {
            return Response.<CommentReplyListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("comment reply list api failed, req={}", requestDTO, e);
            return Response.<CommentReplyListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/comment/hot")
    @Override
    public Response<CommentHotResponseDTO> hot(CommentHotRequestDTO requestDTO) {
        try {
            CommentHotVO vo = commentQueryService.hotComments(requestDTO.getPostId(), requestDTO.getLimit(), requestDTO.getPreloadReplyLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDto(vo));
        } catch (AppException e) {
            return Response.<CommentHotResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("comment hot api failed, req={}", requestDTO, e);
            return Response.<CommentHotResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/comment/{commentId}")
    @Override
    public Response<OperationResultDTO> delete(@PathVariable("commentId") Long commentId) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = interactionService.deleteComment(userId, commentId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("comment delete api failed, commentId={}", commentId, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private CommentViewDTO toDto(CommentViewVO vo) {
        if (vo == null) {
            return null;
        }
        return CommentViewDTO.builder()
                .commentId(vo.getCommentId())
                .postId(vo.getPostId())
                .userId(vo.getUserId())
                .nickname(vo.getNickname() == null ? "" : vo.getNickname())
                .avatarUrl(vo.getAvatarUrl() == null ? "" : vo.getAvatarUrl())
                .rootId(vo.getRootId())
                .parentId(vo.getParentId())
                .replyToId(vo.getReplyToId())
                .content(vo.getContent() == null ? "" : vo.getContent())
                .status(vo.getStatus())
                .likeCount(vo.getLikeCount() == null ? 0L : vo.getLikeCount())
                .replyCount(vo.getReplyCount() == null ? 0L : vo.getReplyCount())
                .createTime(vo.getCreateTime())
                .build();
    }

    private RootCommentViewDTO toDto(RootCommentViewVO vo) {
        if (vo == null) {
            return null;
        }
        List<CommentViewDTO> preview = new ArrayList<>();
        if (vo.getRepliesPreview() != null) {
            for (CommentViewVO c : vo.getRepliesPreview()) {
                CommentViewDTO dto = toDto(c);
                if (dto != null) {
                    preview.add(dto);
                }
            }
        }
        return RootCommentViewDTO.builder()
                .root(toDto(vo.getRoot()))
                .repliesPreview(preview)
                .build();
    }

    private CommentListResponseDTO toDto(RootCommentPageVO vo) {
        List<RootCommentViewDTO> items = new ArrayList<>();
        if (vo != null && vo.getItems() != null) {
            for (RootCommentViewVO v : vo.getItems()) {
                RootCommentViewDTO dto = toDto(v);
                if (dto != null) {
                    items.add(dto);
                }
            }
        }
        return CommentListResponseDTO.builder()
                .pinned(vo == null ? null : toDto(vo.getPinned()))
                .items(items)
                .nextCursor(vo == null ? null : vo.getNextCursor())
                .build();
    }

    private CommentReplyListResponseDTO toDto(ReplyCommentPageVO vo) {
        List<CommentViewDTO> items = new ArrayList<>();
        if (vo != null && vo.getItems() != null) {
            for (CommentViewVO v : vo.getItems()) {
                CommentViewDTO dto = toDto(v);
                if (dto != null) {
                    items.add(dto);
                }
            }
        }
        return CommentReplyListResponseDTO.builder()
                .items(items)
                .nextCursor(vo == null ? null : vo.getNextCursor())
                .build();
    }

    private CommentHotResponseDTO toDto(CommentHotVO vo) {
        List<RootCommentViewDTO> items = new ArrayList<>();
        if (vo != null && vo.getItems() != null) {
            for (RootCommentViewVO v : vo.getItems()) {
                RootCommentViewDTO dto = toDto(v);
                if (dto != null) {
                    items.add(dto);
                }
            }
        }
        return CommentHotResponseDTO.builder()
                .pinned(vo == null ? null : toDto(vo.getPinned()))
                .items(items)
                .build();
    }

    private OperationResultDTO toDto(OperationResultVO vo) {
        if (vo == null) {
            return OperationResultDTO.builder().success(false).status("FAILED").message("").build();
        }
        return OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus() == null ? "" : vo.getStatus())
                .message(vo.getMessage() == null ? "" : vo.getMessage())
                .build();
    }
}
