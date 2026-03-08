package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IContentApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.content.dto.*;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.social.dto.ScheduleCancelRequestDTO;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import cn.nexus.trigger.http.support.UserContext;
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
    @Resource
    private ContentDetailQueryService contentDetailQueryService;

    @PostMapping("/media/upload/session")
    @Override
    public Response<UploadSessionResponseDTO> createUploadSession(@RequestBody UploadSessionRequestDTO requestDTO) {
        try {
            UploadSessionVO vo = contentService.createUploadSession(requestDTO.getFileType(), requestDTO.getFileSize(), requestDTO.getCrc32());
            UploadSessionResponseDTO dto = UploadSessionResponseDTO.builder()
                    .uploadUrl(vo.getUploadUrl())
                    .token(vo.getToken())
                    .sessionId(vo.getSessionId())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<UploadSessionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content create upload session api failed, req={}", requestDTO, e);
            return Response.<UploadSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PutMapping("/content/draft")
    @Override
    public Response<SaveDraftResponseDTO> saveDraft(@RequestBody SaveDraftRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            DraftVO vo = contentService.saveDraft(userId, requestDTO.getDraftId(), requestDTO.getTitle(), requestDTO.getContentText(), requestDTO.getMediaIds());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    SaveDraftResponseDTO.builder().draftId(vo.getDraftId()).build());
        } catch (AppException e) {
            return Response.<SaveDraftResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content save draft api failed, req={}", requestDTO, e);
            return Response.<SaveDraftResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/content/publish")
    @Override
    public Response<PublishContentResponseDTO> publish(@RequestBody PublishContentRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.publish(
                    requestDTO.getPostId(), userId, requestDTO.getTitle(), requestDTO.getText(), requestDTO.getMediaInfo(),
                    requestDTO.getLocation(), requestDTO.getVisibility(), requestDTO.getPostTypes());
            PublishContentResponseDTO dto = PublishContentResponseDTO.builder()
                    .postId(vo.getId())
                    .attemptId(vo.getAttemptId())
                    .versionNum(vo.getVersionNum())
                    .status(vo.getStatus())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<PublishContentResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content publish api failed, req={}", requestDTO, e);
            return Response.<PublishContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/content/publish/attempt/{attemptId}")
    @Override
    public Response<PublishAttemptResponseDTO> publishAttempt(@PathVariable("attemptId") Long attemptId,
                                                              @RequestParam("userId") Long ignoredUserId) {
        try {
            Long userId = UserContext.requireUserId();
            ContentPublishAttemptEntity attempt = contentService.getPublishAttemptAudit(attemptId, userId);
            if (attempt == null) {
                return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), null);
            }
            PublishAttemptResponseDTO dto = PublishAttemptResponseDTO.builder()
                    .attemptId(attempt.getAttemptId())
                    .postId(attempt.getPostId())
                    .userId(attempt.getUserId())
                    .idempotentToken(attempt.getIdempotentToken())
                    .transcodeJobId(attempt.getTranscodeJobId())
                    .attemptStatus(attempt.getAttemptStatus())
                    .riskStatus(attempt.getRiskStatus())
                    .transcodeStatus(attempt.getTranscodeStatus())
                    .publishedVersionNum(attempt.getPublishedVersionNum())
                    .errorCode(attempt.getErrorCode())
                    .errorMessage(attempt.getErrorMessage())
                    .createTime(attempt.getCreateTime())
                    .updateTime(attempt.getUpdateTime())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<PublishAttemptResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content publish attempt api failed, attemptId={}", attemptId, e);
            return Response.<PublishAttemptResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/content/{postId}")
    @Override
    public Response<OperationResultDTO> delete(@PathVariable("postId") Long postId, @RequestBody DeleteContentRequestDTO ignoredRequestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.delete(userId, postId);
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content delete api failed, postId={}", postId, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/content/{postId}")
    public Response<ContentDetailResponseDTO> detail(@PathVariable("postId") Long postId,
                                                     @RequestParam(value = "userId", required = false) Long ignoredUserId) {
        try {
            UserContext.requireUserId();
            ContentDetailResponseDTO dto = contentDetailQueryService.query(postId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<ContentDetailResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content detail api failed, postId={}", postId, e);
            return Response.<ContentDetailResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/content/schedule")
    @Override
    public Response<ScheduleContentResponseDTO> schedule(@RequestBody ScheduleContentRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.schedule(userId, requestDTO.getPostId(), requestDTO.getPublishTime(), requestDTO.getTimezone());
            long delayMs = requestDTO.getPublishTime() == null ? 0 : requestDTO.getPublishTime() - System.currentTimeMillis();
            contentScheduleProducer.sendDelay(vo.getId(), delayMs);
            ScheduleContentResponseDTO dto = ScheduleContentResponseDTO.builder()
                    .taskId(vo.getId())
                    .postId(requestDTO.getPostId())
                    .status(vo.getStatus())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<ScheduleContentResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content schedule api failed, req={}", requestDTO, e);
            return Response.<ScheduleContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/content/schedule/cancel")
    public Response<OperationResultDTO> cancelSchedule(@RequestBody ScheduleCancelRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.cancelSchedule(requestDTO.getTaskId(), userId, requestDTO.getReason());
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content cancel schedule api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PatchMapping("/content/schedule")
    @Override
    public Response<OperationResultDTO> updateSchedule(@RequestBody cn.nexus.api.social.content.dto.ScheduleUpdateRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.updateSchedule(requestDTO.getTaskId(), userId, requestDTO.getPublishTime(), requestDTO.getContentData(), requestDTO.getReason());
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content update schedule api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PatchMapping("/content/draft/{draftId}")
    @Override
    public Response<DraftSyncResponseDTO> syncDraft(@PathVariable("draftId") Long draftId, @RequestBody DraftSyncRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            DraftSyncVO vo = contentService.syncDraft(draftId, userId, requestDTO.getTitle(), requestDTO.getDiffContent(), requestDTO.getClientVersion(), requestDTO.getDeviceId(), requestDTO.getMediaIds());
            DraftSyncResponseDTO dto = DraftSyncResponseDTO.builder()
                    .serverVersion(vo.getServerVersion() == null ? null : String.valueOf(vo.getServerVersion()))
                    .syncTime(vo.getSyncTime())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<DraftSyncResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content sync draft api failed, draftId={}, req={}", draftId, requestDTO, e);
            return Response.<DraftSyncResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/content/{postId}/history")
    @Override
    public Response<ContentHistoryResponseDTO> history(@PathVariable("postId") Long postId,
                                                       @RequestParam(value = "userId", required = false) Long ignoredUserId,
                                                       @RequestParam(value = "limit", required = false) Integer limit,
                                                       @RequestParam(value = "offset", required = false) Integer offset) {
        try {
            Long userId = UserContext.requireUserId();
            ContentHistoryVO vo = contentService.history(postId, userId, limit, offset);
            ContentHistoryResponseDTO dto = ContentHistoryResponseDTO.builder()
                    .versions(vo.getVersions().stream()
                            .map(v -> ContentHistoryResponseDTO.ContentVersionDTO.builder()
                                    .versionId(v.getVersionId())
                                    .title(v.getTitle())
                                    .content(v.getContent())
                                    .time(v.getTime())
                                    .build())
                            .toList())
                    .nextCursor(vo.getNextCursor())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<ContentHistoryResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content history api failed, postId={}, limit={}, offset={}", postId, limit, offset, e);
            return Response.<ContentHistoryResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/content/{postId}/rollback")
    @Override
    public Response<OperationResultDTO> rollback(@PathVariable("postId") Long postId, @RequestBody ContentRollbackRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = contentService.rollback(postId, userId, requestDTO.getTargetVersionId());
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content rollback api failed, postId={}, req={}", postId, requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @Override
    @GetMapping("/content/schedule/{taskId}")
    public Response<ScheduleAuditResponseDTO> scheduleAudit(@PathVariable("taskId") Long taskId,
                                                            @RequestParam("userId") Long ignoredUserId) {
        try {
            Long userId = UserContext.requireUserId();
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
        } catch (AppException e) {
            return Response.<ScheduleAuditResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("content schedule audit api failed, taskId={}", taskId, e);
            return Response.<ScheduleAuditResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
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
