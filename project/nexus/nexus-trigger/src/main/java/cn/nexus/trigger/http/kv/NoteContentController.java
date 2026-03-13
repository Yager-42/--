package cn.nexus.trigger.http.kv;

import cn.nexus.api.kv.note.dto.KvNoteContentAddRequestDTO;
import cn.nexus.api.kv.note.dto.KvNoteContentDeleteRequestDTO;
import cn.nexus.api.kv.note.dto.KvNoteContentFindRequestDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KV: note(post) content.
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/kv/note/content")
@RequiredArgsConstructor
public class NoteContentController {

    private final IPostContentKvPort postContentKvPort;

    @PostMapping("/add")
    public Response<Object> add(@RequestBody KvNoteContentAddRequestDTO requestDTO) {
        try {
            postContentKvPort.add(requestDTO == null ? null : requestDTO.getUuid(), requestDTO == null ? null : requestDTO.getContent());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv note content add failed, req={}", requestDTO, e);
            return Response.builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/find")
    public Response<String> find(@RequestBody KvNoteContentFindRequestDTO requestDTO) {
        try {
            String content = postContentKvPort.find(requestDTO == null ? null : requestDTO.getUuid());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), content);
        } catch (AppException e) {
            return Response.<String>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv note content find failed, req={}", requestDTO, e);
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/delete")
    public Response<Object> delete(@RequestBody KvNoteContentDeleteRequestDTO requestDTO) {
        try {
            postContentKvPort.delete(requestDTO == null ? null : requestDTO.getUuid());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        } catch (AppException e) {
            return Response.builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("kv note content delete failed, req={}", requestDTO, e);
            return Response.builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }
}
