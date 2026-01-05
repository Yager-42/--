package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.*;

/**
 * 内容领域服务。
 */
public interface IContentService {

    UploadSessionVO createUploadSession(String fileType, Long fileSize, String crc32);

    DraftVO saveDraft(Long userId, String contentText, java.util.List<String> mediaIds);

    PublishResultVO publish(Long postId, Long userId, String text, String mediaInfo, String location, String visibility);

    OperationResultVO delete(Long userId, Long postId);

    OperationResultVO schedule(Long userId, String contentData, Long publishTime, String timezone);

    OperationResultVO syncDraft(Long draftId, String diffContent, String clientVersion, String deviceId);

    ContentHistoryVO history(Long postId, Long userId, Integer limit, Integer offset);

    OperationResultVO rollback(Long postId, Long userId, Long targetVersionId);

    /**
     * 定时发布扫描处理器，占位：扫描到期任务并发布。
     */
    OperationResultVO processSchedules(Long now, Integer limit);

    /**
     * 执行单个定时任务（MQ延时到达触发）。
     */
    OperationResultVO executeSchedule(Long taskId);

    /**
     * 取消定时任务。
     */
    OperationResultVO cancelSchedule(Long taskId, Long userId, String reason);

    /**
     * 变更定时任务。
     */
    OperationResultVO updateSchedule(Long taskId, Long userId, Long publishTime, String contentData, String reason);

    /**
     * 获取定时任务审计信息。
     */
    ContentScheduleEntity getScheduleAudit(Long taskId, Long userId);

    /**
     * 触发内容版本存储重平衡（按需重建新的基准版本）。
     */
    OperationResultVO rebalanceStorage(Long postId);
}
