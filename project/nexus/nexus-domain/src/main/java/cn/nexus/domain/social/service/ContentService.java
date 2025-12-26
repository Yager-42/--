package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容领域服务实现。
 */
@Service
@RequiredArgsConstructor
public class ContentService implements IContentService {

    private final ISocialIdPort socialIdPort;

    @Override
    public UploadSessionVO createUploadSession(String fileType, Long fileSize, String crc32) {
        return UploadSessionVO.builder()
                .uploadUrl("https://mock-upload/" + socialIdPort.nextId())
                .token("token-" + socialIdPort.nextId())
                .sessionId("session-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public DraftVO saveDraft(Long userId, String contentText, List<String> mediaIds) {
        return DraftVO.builder().draftId(socialIdPort.nextId()).build();
    }

    @Override
    public PublishResultVO publish(Long userId, String text, String mediaInfo, String location, String visibility) {
        return PublishResultVO.builder()
                .postId(socialIdPort.nextId())
                .status("PUBLISHED")
                .build();
    }

    @Override
    public OperationResultVO delete(Long userId, Long postId) {
        return OperationResultVO.builder()
                .success(true)
                .id(postId)
                .status("DELETED")
                .message("已删除")
                .build();
    }

    @Override
    public OperationResultVO schedule(String contentData, Long publishTime, String timezone) {
        return OperationResultVO.builder()
                .success(true)
                .id(socialIdPort.nextId())
                .status("SCHEDULED")
                .message("定时任务已创建")
                .build();
    }

    @Override
    public OperationResultVO syncDraft(Long draftId, String diffContent, String clientVersion, String deviceId) {
        return OperationResultVO.builder()
                .success(true)
                .id(draftId)
                .status("SYNCED")
                .message("serverVersion-" + clientVersion)
                .build();
    }

    @Override
    public ContentHistoryVO history(Long postId, Long userId, Integer limit) {
        List<ContentHistoryVO.ContentVersionVO> versions = new ArrayList<>();
        versions.add(ContentHistoryVO.ContentVersionVO.builder()
                .versionId(socialIdPort.nextId())
                .content("原始内容")
                .time(socialIdPort.now())
                .build());
        versions.add(ContentHistoryVO.ContentVersionVO.builder()
                .versionId(socialIdPort.nextId())
                .content("最新内容")
                .time(socialIdPort.now())
                .build());
        return ContentHistoryVO.builder().versions(versions).build();
    }

    @Override
    public OperationResultVO rollback(Long postId, Long targetVersionId) {
        return OperationResultVO.builder()
                .success(true)
                .id(targetVersionId)
                .status("ROLLED_BACK")
                .message("已回滚")
                .build();
    }
}
