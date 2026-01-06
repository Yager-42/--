package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IContentApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.content.dto.*;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.social.dto.ScheduleCancelRequestDTO;
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
    @Resource
    private cn.nexus.trigger.mq.producer.ContentScheduleProducer contentScheduleProducer;

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
        OperationResultVO vo = contentService.publish(
                requestDTO.getPostId(), requestDTO.getUserId(), requestDTO.getText(), requestDTO.getMediaInfo(),
                requestDTO.getLocation(), requestDTO.getVisibility());
        PublishContentResponseDTO dto = PublishContentResponseDTO.builder()
                .postId(vo.getId())
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
        OperationResultVO vo = contentService.schedule(requestDTO.getUserId(), requestDTO.getContentData(), requestDTO.getPublishTime(), requestDTO.getTimezone());
        long delayMs = requestDTO.getPublishTime() == null ? 0 : requestDTO.getPublishTime() - System.currentTimeMillis();
        contentScheduleProducer.sendDelay(vo.getId(), delayMs);
        ScheduleContentResponseDTO dto = ScheduleContentResponseDTO.builder()
                .taskId(vo.getId())
                .status(vo.getStatus())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PostMapping("/content/schedule/cancel")
    public Response<OperationResultDTO> cancelSchedule(@RequestBody ScheduleCancelRequestDTO requestDTO) {
        OperationResultVO vo = contentService.cancelSchedule(requestDTO.getTaskId(), requestDTO.getUserId(), requestDTO.getReason());
        return toOperationResult(vo);
    }

    @PatchMapping("/content/schedule")
    @Override
    public Response<OperationResultDTO> updateSchedule(@RequestBody cn.nexus.api.social.content.dto.ScheduleUpdateRequestDTO requestDTO) {
        OperationResultVO vo = contentService.updateSchedule(requestDTO.getTaskId(), requestDTO.getUserId(), requestDTO.getPublishTime(), requestDTO.getContentData(), requestDTO.getReason());
        return toOperationResult(vo);
    }

    @PatchMapping("/content/draft/{draftId}")
    @Override
    public Response<DraftSyncResponseDTO> syncDraft(@PathVariable("draftId") Long draftId, @RequestBody DraftSyncRequestDTO requestDTO) {
        OperationResultVO vo = contentService.syncDraft(draftId, requestDTO.getDiffContent(), requestDTO.getClientVersion(), requestDTO.getDeviceId(), requestDTO.getMediaIds());
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
                                                       @RequestParam(value = "limit", required = false) Integer limit,
                                                       @RequestParam(value = "offset", required = false) Integer offset) {
        ContentHistoryVO vo = contentService.history(postId, userId, limit, offset);
        ContentHistoryResponseDTO dto = ContentHistoryResponseDTO.builder()
                .versions(vo.getVersions().stream()
                        .map(v -> ContentHistoryResponseDTO.ContentVersionDTO.builder()
                                .versionId(v.getVersionId())
                                .content(v.getContent())
                                .time(v.getTime())
                                .build())
                        .toList())
                .nextCursor(vo.getNextCursor())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PostMapping("/content/{postId}/rollback")
    @Override
    public Response<OperationResultDTO> rollback(@PathVariable("postId") Long postId, @RequestBody ContentRollbackRequestDTO requestDTO) {
        OperationResultVO vo = contentService.rollback(postId, requestDTO.getUserId(), requestDTO.getTargetVersionId());
        return toOperationResult(vo);
    }

    @Override
    @GetMapping("/content/schedule/{taskId}")
    public Response<ScheduleAuditResponseDTO> scheduleAudit(@PathVariable("taskId") Long taskId,
                                                            @RequestParam("userId") Long userId) {
        ContentScheduleEntity task = contentService.getScheduleAudit(taskId, userId);
        if (task == null) {
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
        }
        ScheduleAuditResponseDTO dto = ScheduleAuditResponseDTO.builder()
                .taskId(task.getTaskId())
                .userId(task.getUserId())
                .scheduleTime(task.getScheduleTime())
                .status(task.getStatus())
                .retryCount(task.getRetryCount())
                .isCanceled(task.getIsCanceled())
                .lastError(task.getLastError())
                .alarmSent(task.getAlarmSent())
                .contentData(task.getContentData())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
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
