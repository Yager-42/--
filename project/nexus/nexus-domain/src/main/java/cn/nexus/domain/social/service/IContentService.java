package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.*;

/**
 * 内容领域服务。
 */
public interface IContentService {

    UploadSessionVO createUploadSession(String fileType, Long fileSize, String crc32);

    DraftVO saveDraft(Long userId, String contentText, java.util.List<String> mediaIds);

    PublishResultVO publish(Long userId, String text, String mediaInfo, String location, String visibility);

    OperationResultVO delete(Long userId, Long postId);

    OperationResultVO schedule(String contentData, Long publishTime, String timezone);

    OperationResultVO syncDraft(Long draftId, String diffContent, String clientVersion, String deviceId);

    ContentHistoryVO history(Long postId, Long userId, Integer limit);

    OperationResultVO rollback(Long postId, Long targetVersionId);
}
