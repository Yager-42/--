package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IContentApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.content.dto.*;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 内容与媒体接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class ContentController implements IContentApi {

    @Resource
    private IContentService contentService;

    @PostMapping("/media/upload/session")
    @Override
    public Response<UploadSessionResponseDTO> createUploadSession(@RequestBody UploadSessionRequestDTO requestDTO) {
        UploadSessionVO vo = contentService.createUploadSession(requestDTO.getFileType(), requestDTO.getFileSize(), requestDTO.getCrc32());
        UploadSessionResponseDTO dto = UploadSessionResponseDTO.builder()
                .uploadUrl(vo.getUploadUrl())
                .token(vo.getToken())
                .sessionId(vo.getSessionId())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PutMapping("/content/draft")
    @Override
    public Response<SaveDraftResponseDTO> saveDraft(@RequestBody SaveDraftRequestDTO requestDTO) {
        DraftVO vo = contentService.saveDraft(requestDTO.getUserId(), requestDTO.getContentText(), requestDTO.getMediaIds());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                SaveDraftResponseDTO.builder().draftId(vo.getDraftId()).build());
    }

    @PostMapping("/content/publish")
    @Override
    public Response<PublishContentResponseDTO> publish(@RequestBody PublishContentRequestDTO requestDTO) {
        PublishResultVO vo = contentService.publish(
                requestDTO.getUserId(), requestDTO.getText(), requestDTO.getMediaInfo(),
                requestDTO.getLocation(), requestDTO.getVisibility());
        PublishContentResponseDTO dto = PublishContentResponseDTO.builder()
                .postId(vo.getPostId())
                .status(vo.getStatus())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @DeleteMapping("/content/{postId}")
    @Override
    public Response<OperationResultDTO> delete(@PathVariable("postId") Long postId, @RequestBody DeleteContentRequestDTO requestDTO) {
        OperationResultVO vo = contentService.delete(requestDTO.getUserId(), postId);
        return toOperationResult(vo);
    }

    @PostMapping("/content/schedule")
    @Override
    public Response<ScheduleContentResponseDTO> schedule(@RequestBody ScheduleContentRequestDTO requestDTO) {
        OperationResultVO vo = contentService.schedule(requestDTO.getContentData(), requestDTO.getPublishTime(), requestDTO.getTimezone());
        ScheduleContentResponseDTO dto = ScheduleContentResponseDTO.builder()
                .taskId(vo.getId())
                .status(vo.getStatus())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PatchMapping("/content/draft/{draftId}")
    @Override
    public Response<DraftSyncResponseDTO> syncDraft(@PathVariable("draftId") Long draftId, @RequestBody DraftSyncRequestDTO requestDTO) {
        OperationResultVO vo = contentService.syncDraft(draftId, requestDTO.getDiffContent(), requestDTO.getClientVersion(), requestDTO.getDeviceId());
        DraftSyncResponseDTO dto = DraftSyncResponseDTO.builder()
                .serverVersion(vo.getMessage())
                .syncTime(vo.getId() == null ? null : vo.getId())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @GetMapping("/content/{postId}/history")
    @Override
    public Response<ContentHistoryResponseDTO> history(@PathVariable("postId") Long postId,
                                                       @RequestParam(value = "userId", required = false) Long userId,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        ContentHistoryVO vo = contentService.history(postId, userId, limit);
        ContentHistoryResponseDTO dto = ContentHistoryResponseDTO.builder()
                .versions(vo.getVersions().stream()
                        .map(v -> ContentHistoryResponseDTO.ContentVersionDTO.builder()
                                .versionId(v.getVersionId())
                                .content(v.getContent())
                                .time(v.getTime())
                                .build())
                        .toList())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PostMapping("/content/{postId}/rollback")
    @Override
    public Response<OperationResultDTO> rollback(@PathVariable("postId") Long postId, @RequestBody ContentRollbackRequestDTO requestDTO) {
        OperationResultVO vo = contentService.rollback(postId, requestDTO.getTargetVersionId());
        return toOperationResult(vo);
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
