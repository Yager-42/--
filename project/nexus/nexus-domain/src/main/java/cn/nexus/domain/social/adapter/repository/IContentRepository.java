package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;

import java.util.List;

/**
 * 内容/媒体仓储接口，抽象底层存储。
 */
public interface IContentRepository {

    ContentDraftEntity saveDraft(ContentDraftEntity draft);

    ContentDraftEntity findDraft(Long draftId);

    ContentPostEntity savePost(ContentPostEntity post);

    ContentPostEntity findPost(Long postId);

    boolean updatePostStatusAndContent(Long postId, Integer status, Integer versionNum, Boolean edited,
                                       String contentText, String mediaInfo, String locationInfo, Integer visibility);

    void saveHistory(ContentHistoryEntity history);

    List<ContentHistoryEntity> listHistory(Long postId, Integer limit);

    ContentHistoryEntity findHistoryVersion(Long postId, Integer versionNum);

    boolean softDelete(Long postId, Long userId);

    ContentScheduleEntity createSchedule(ContentScheduleEntity schedule);

    boolean updateScheduleStatus(Long taskId, Integer status, Integer retryCount);

    java.util.List<ContentScheduleEntity> listPendingSchedules(Long beforeTime, Integer limit);
}
