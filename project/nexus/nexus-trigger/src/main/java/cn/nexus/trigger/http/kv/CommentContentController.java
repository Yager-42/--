package cn.nexus.trigger.http.kv;

import cn.nexus.api.kv.comment.dto.*;
import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * KV: comment content.
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/kv/comment/content")
@RequiredArgsConstructor
public class CommentContentController {

    private final ICommentContentKvPort commentContentKvPort;

    @PostMapping("/batchAdd")
    public Response<Object> batchAdd(@RequestBody KvCommentContentBatchAddRequestDTO requestDTO) {
        try {
            List<KvCommentContentDTO> comments = requestDTO == null ? null : requestDTO.getComments();
            List<CommentContentItemVO> list = new ArrayList<>(comments == null ? 0 : comments.size());
            if (comments != null) {
                for (KvCommentContentDTO c : comments) {
                    if (c == null) {
                        continue;
                    }
                    list.add(CommentContentItemVO.builder()
                            .postId(c.getNoteId())
                            .yearMonth(c.getYearMonth())
                            .contentId(c.getContentId())
                            .content(c.getContent())
                            .build());
                }
            }
            commentContentKvPort.batchAdd(list);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv comment content batchAdd failed, req={}", requestDTO, e);
            return Response.builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/batchFind")
    public Response<List<KvCommentContentFoundDTO>> batchFind(@RequestBody KvCommentContentBatchFindRequestDTO requestDTO) {
        try {
            Long noteId = requestDTO == null ? null : requestDTO.getNoteId();
            List<KvCommentContentKeyDTO> keys = requestDTO == null ? null : requestDTO.getCommentContentKeys();
            List<CommentContentKeyVO> list = new ArrayList<>(keys == null ? 0 : keys.size());
            if (keys != null) {
                for (KvCommentContentKeyDTO k : keys) {
                    if (k == null) {
                        continue;
                    }
                    list.add(CommentContentKeyVO.builder().yearMonth(k.getYearMonth()).contentId(k.getContentId()).build());
                }
            }
            List<cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO> res = commentContentKvPort.batchFind(noteId, list);
            List<KvCommentContentFoundDTO> out = new ArrayList<>(res == null ? 0 : res.size());
            if (res != null) {
                for (cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO r : res) {
                    if (r == null) {
                        continue;
                    }
                    out.add(KvCommentContentFoundDTO.builder().contentId(r.getContentId()).content(r.getContent()).build());
                }
            }
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), out);
        } catch (AppException e) {
            return Response.<List<KvCommentContentFoundDTO>>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv comment content batchFind failed, req={}", requestDTO, e);
            return Response.<List<KvCommentContentFoundDTO>>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/delete")
    public Response<Object> delete(@RequestBody KvCommentContentDeleteRequestDTO requestDTO) {
        try {
            commentContentKvPort.delete(
                    requestDTO == null ? null : requestDTO.getNoteId(),
                    requestDTO == null ? null : requestDTO.getYearMonth(),
                    requestDTO == null ? null : requestDTO.getContentId()
            );
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv comment content delete failed, req={}", requestDTO, e);
            return Response.builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }
}
