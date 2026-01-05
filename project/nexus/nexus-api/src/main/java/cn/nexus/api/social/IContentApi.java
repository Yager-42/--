package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.content.dto.*;

/**
 * 内容/媒体相关接口定义。
 */
public interface IContentApi {

    Response<UploadSessionResponseDTO> createUploadSession(UploadSessionRequestDTO requestDTO);

    Response<SaveDraftResponseDTO> saveDraft(SaveDraftRequestDTO requestDTO);

    Response<PublishContentResponseDTO> publish(PublishContentRequestDTO requestDTO);

    Response<OperationResultDTO> delete(Long postId, DeleteContentRequestDTO requestDTO);

    Response<ScheduleContentResponseDTO> schedule(ScheduleContentRequestDTO requestDTO);

    Response<DraftSyncResponseDTO> syncDraft(Long draftId, DraftSyncRequestDTO requestDTO);

    Response<ContentHistoryResponseDTO> history(Long postId, Long userId, Integer limit, Integer offset);

    Response<OperationResultDTO> rollback(Long postId, ContentRollbackRequestDTO requestDTO);

    Response<OperationResultDTO> updateSchedule(cn.nexus.api.social.content.dto.ScheduleUpdateRequestDTO requestDTO);

    Response<ScheduleAuditResponseDTO> scheduleAudit(Long taskId, Long userId);
}
