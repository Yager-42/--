package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.domain.social.adapter.repository.IContentPublishAttemptRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.model.entity.ContentDraftEntity;
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.ContentScheduleEntity;
import cn.nexus.domain.social.model.valobj.ContentHistoryVO;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptRiskStatusEnumVO;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptStatusEnumVO;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptTranscodeStatusEnumVO;
import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ContentServiceTest.TestConfig.class)
class ContentServiceTest {

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        ISocialIdPort socialIdPort() {
            return Mockito.mock(ISocialIdPort.class);
        }

        @Bean
        IContentRepository contentRepository() {
            return Mockito.mock(IContentRepository.class);
        }

        @Bean
        IPostContentKvPort postContentKvPort() {
            return Mockito.mock(IPostContentKvPort.class);
        }

        @Bean
        IContentPublishAttemptRepository contentPublishAttemptRepository() {
            return Mockito.mock(IContentPublishAttemptRepository.class);
        }

        @Bean
        IMediaStoragePort mediaStoragePort() {
            return Mockito.mock(IMediaStoragePort.class);
        }

        @Bean
        IMediaTranscodePort mediaTranscodePort() {
            return Mockito.mock(IMediaTranscodePort.class);
        }

        @Bean
        IContentEventOutboxPort contentEventOutboxPort() {
            return Mockito.mock(IContentEventOutboxPort.class);
        }

        @Bean
        IRelationEventOutboxRepository relationEventOutboxRepository() {
            return Mockito.mock(IRelationEventOutboxRepository.class);
        }

        @Bean
        IContentCacheEvictPort contentCacheEvictPort() {
            return Mockito.mock(IContentCacheEvictPort.class);
        }

        @Bean
        IRiskService riskService() {
            return Mockito.mock(IRiskService.class);
        }

        @Bean
        RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }

        @Bean
        ContentService contentService(ISocialIdPort socialIdPort,
                                      IContentRepository contentRepository,
                                      IPostContentKvPort postContentKvPort,
                                      IContentPublishAttemptRepository contentPublishAttemptRepository,
                                      IMediaStoragePort mediaStoragePort,
                                      IMediaTranscodePort mediaTranscodePort,
                                      IContentEventOutboxPort contentEventOutboxPort,
                                      IRelationEventOutboxRepository relationEventOutboxRepository,
                                      IContentCacheEvictPort contentCacheEvictPort,
                                      IRiskService riskService,
                                      RedissonClient redissonClient) {
            return new ContentService(
                    socialIdPort,
                    contentRepository,
                    postContentKvPort,
                    contentPublishAttemptRepository,
                    mediaStoragePort,
                    mediaTranscodePort,
                    contentEventOutboxPort,
                    relationEventOutboxRepository,
                    contentCacheEvictPort,
                    riskService,
                    redissonClient
            );
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private IContentService contentService;
    @org.springframework.beans.factory.annotation.Autowired
    private ISocialIdPort socialIdPort;
    @org.springframework.beans.factory.annotation.Autowired
    private IContentRepository contentRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IPostContentKvPort postContentKvPort;
    @org.springframework.beans.factory.annotation.Autowired
    private IContentPublishAttemptRepository contentPublishAttemptRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IMediaStoragePort mediaStoragePort;
    @org.springframework.beans.factory.annotation.Autowired
    private IContentEventOutboxPort contentEventOutboxPort;
    @org.springframework.beans.factory.annotation.Autowired
    private IRelationEventOutboxRepository relationEventOutboxRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IContentCacheEvictPort contentCacheEvictPort;
    @org.springframework.beans.factory.annotation.Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(
                socialIdPort,
                contentRepository,
                postContentKvPort,
                contentPublishAttemptRepository,
                mediaStoragePort,
                contentEventOutboxPort,
                relationEventOutboxRepository,
                contentCacheEvictPort,
                redissonClient
        );
    }

    @Test
    void createUploadSession_shouldFallbackUnknownFileTypeToOctetStream() {
        UploadSessionVO expected = UploadSessionVO.builder()
                .sessionId("session-77")
                .token("token")
                .uploadUrl("url")
                .build();
        when(socialIdPort.nextId()).thenReturn(77L);
        when(mediaStoragePort.generateUploadSession("session-77", "application/octet-stream", 1024L, "crc"))
                .thenReturn(expected);

        UploadSessionVO result = contentService.createUploadSession("text/plain", 1024L, "crc");

        assertSame(expected, result);
        verify(mediaStoragePort).generateUploadSession("session-77", "application/octet-stream", 1024L, "crc");
    }

    @Test
    void saveDraft_shouldThrowWhenContentAndMediaAreEmpty() {
        AppException exception =
                assertThrows(AppException.class, () -> contentService.saveDraft(11L, null, "title", "   ", null));

        assertEquals("content 不能为空", exception.getInfo());
    }

    @Test
    void saveDraft_shouldRejectWhenDraftOwnedByAnotherUser() {
        when(contentRepository.findDraft(99L)).thenReturn(ContentDraftEntity.builder().draftId(99L).userId(22L).build());

        AppException exception =
                assertThrows(AppException.class, () -> contentService.saveDraft(11L, 99L, "title", "body", null));

        assertEquals("NO_PERMISSION", exception.getInfo());
    }

    @Test
    void publish_shouldReuseActiveAttemptWithoutCreatingNewOne() {
        RLock lock = Mockito.mock(RLock.class);
        ContentPublishAttemptEntity activeAttempt = ContentPublishAttemptEntity.builder()
                .attemptId(77L)
                .postId(101L)
                .userId(11L)
                .attemptStatus(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
                .riskStatus(ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode())
                .build();
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(contentPublishAttemptRepository.findLatestActiveAttempt(101L, 11L)).thenReturn(activeAttempt);

        cn.nexus.domain.social.model.valobj.OperationResultVO result =
                contentService.publish(101L, 11L, "title", "body", null, null, "PUBLIC", null);

        assertEquals(77L, result.getAttemptId());
        verify(contentPublishAttemptRepository, never()).create(any());
        verify(relationEventOutboxRepository, never()).save(anyLong(), anyString(), anyString());
    }

    @Test
    void publish_newPublishedPost_shouldWritePostCounterOutboxOnce() {
        RLock lock = Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(socialIdPort.nextId()).thenReturn(500L, 501L, 502L);
        when(socialIdPort.now()).thenReturn(1000L);
        when(contentPublishAttemptRepository.updateAttemptStatus(
                eq(500L),
                eq(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode()),
                eq(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode()),
                eq(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode()),
                any(),
                eq(1),
                any(),
                any(),
                eq(ContentPublishAttemptStatusEnumVO.CREATED.getCode())
        )).thenReturn(true);

        cn.nexus.domain.social.model.valobj.OperationResultVO result =
                contentService.publish(101L, 11L, "title", "body", null, null, "PUBLIC", null);

        assertEquals("PUBLISHED", result.getStatus());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(relationEventOutboxRepository).save(anyLong(), eq("POST"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"sourceId\":11"));
        assertTrue(payload.contains("\"targetId\":101"));
        assertTrue(payload.contains("\"projectionKey\":\"post:101\""));
        assertTrue(payload.contains("\"projectionVersion\":1"));
        assertTrue(payload.contains("\"status\":\"PUBLISHED\""));
    }

    @Test
    void publish_existingPublishedPost_shouldNotDoubleCountPostCounter() {
        RLock lock = Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(contentRepository.findPostForUpdate(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(2)
                .versionNum(2)
                .contentUuid("old-uuid")
                .build());
        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(2)
                .versionNum(2)
                .contentUuid("old-uuid")
                .build());
        when(socialIdPort.nextId()).thenReturn(500L, 501L);
        when(socialIdPort.now()).thenReturn(1000L);
        when(contentRepository.updatePostStatusAndContent(
                eq(101L), eq(2), eq(3), eq(false), eq("title"), any(), anyString(), any(), any(), eq(0)
        )).thenReturn(true);
        when(contentPublishAttemptRepository.updateAttemptStatus(
                eq(500L),
                eq(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode()),
                eq(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode()),
                eq(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode()),
                any(),
                eq(3),
                any(),
                any(),
                eq(ContentPublishAttemptStatusEnumVO.CREATED.getCode())
        )).thenReturn(true);

        contentService.publish(101L, 11L, "title", "body", null, null, "PUBLIC", null);

        verify(relationEventOutboxRepository, never()).save(anyLong(), eq("POST"), anyString());
    }

    @Test
    void applyRiskReviewResult_block_shouldEvictCacheButNotGenerateSummary() {
        ContentPublishAttemptEntity attempt = ContentPublishAttemptEntity.builder()
                .attemptId(1L)
                .postId(101L)
                .userId(11L)
                .attemptStatus(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
                .riskStatus(ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode())
                .transcodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode())
                .publishedVersionNum(3)
                .build();

        when(contentPublishAttemptRepository.findByAttemptId(1L)).thenReturn(attempt);
        when(contentRepository.updatePostStatusIfMatchVersion(101L, 3, 1, 3)).thenReturn(true);
        when(contentPublishAttemptRepository.updateAttemptStatus(
                eq(1L), eq(ContentPublishAttemptStatusEnumVO.RISK_REJECTED.getCode()),
                eq(ContentPublishAttemptRiskStatusEnumVO.REJECTED.getCode()),
                eq(ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode()),
                any(), eq(3), anyString(), eq("风控拦截"),
                eq(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
        )).thenReturn(true);
        when(socialIdPort.now()).thenReturn(1000L);

        contentService.applyRiskReviewResult(1L, "BLOCK", "RISK");

        verify(contentEventOutboxPort).savePostUpdated(eq(101L), eq(11L), eq(3), eq(1000L));
        verify(contentCacheEvictPort).evictPost(101L);
        verify(contentEventOutboxPort, never()).savePostSummaryGenerate(anyLong(), anyLong(), anyInt(), anyLong());
        verify(relationEventOutboxRepository, never()).save(anyLong(), eq("POST"), anyString());
    }

    @Test
    void applyRiskReviewResult_pass_shouldWritePostCounterOutbox() {
        ContentPublishAttemptEntity attempt = ContentPublishAttemptEntity.builder()
                .attemptId(1L)
                .postId(101L)
                .userId(11L)
                .attemptStatus(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
                .riskStatus(ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode())
                .transcodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode())
                .publishedVersionNum(3)
                .build();

        when(contentPublishAttemptRepository.findByAttemptId(1L)).thenReturn(attempt);
        when(contentRepository.findPostMeta(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(1)
                .publishTime(null)
                .build());
        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(800L);
        when(contentRepository.updatePostStatusAndPublishTimeIfMatchVersion(101L, 2, 1, 3, 1000L))
                .thenReturn(true);
        when(contentPublishAttemptRepository.updateAttemptStatus(
                eq(1L), eq(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode()),
                eq(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode()),
                eq(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode()),
                any(), eq(3), any(), any(),
                eq(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
        )).thenReturn(true);

        contentService.applyRiskReviewResult(1L, "PASS", null);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(relationEventOutboxRepository).save(eq(800L), eq("POST"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"sourceId\":11"));
        assertTrue(payload.contains("\"targetId\":101"));
        assertTrue(payload.contains("\"projectionKey\":\"post:101\""));
        assertTrue(payload.contains("\"projectionVersion\":3"));
        assertTrue(payload.contains("\"status\":\"PUBLISHED\""));
    }

    @Test
    void applyRiskReviewResult_pass_whenAlreadyPublishedBeforeReview_shouldNotWritePostCounterOutbox() {
        ContentPublishAttemptEntity attempt = ContentPublishAttemptEntity.builder()
                .attemptId(1L)
                .postId(101L)
                .userId(11L)
                .attemptStatus(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
                .riskStatus(ContentPublishAttemptRiskStatusEnumVO.REVIEW_REQUIRED.getCode())
                .transcodeStatus(ContentPublishAttemptTranscodeStatusEnumVO.NOT_STARTED.getCode())
                .publishedVersionNum(3)
                .build();

        when(contentPublishAttemptRepository.findByAttemptId(1L)).thenReturn(attempt);
        when(contentRepository.findPostMeta(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(1)
                .publishTime(999L)
                .build());
        when(socialIdPort.now()).thenReturn(1000L);
        when(contentRepository.updatePostStatusAndPublishTimeIfMatchVersion(101L, 2, 1, 3, 1000L))
                .thenReturn(true);
        when(contentPublishAttemptRepository.updateAttemptStatus(
                eq(1L), eq(ContentPublishAttemptStatusEnumVO.PUBLISHED.getCode()),
                eq(ContentPublishAttemptRiskStatusEnumVO.PASSED.getCode()),
                eq(ContentPublishAttemptTranscodeStatusEnumVO.DONE.getCode()),
                any(), eq(3), any(), any(),
                eq(ContentPublishAttemptStatusEnumVO.PENDING_REVIEW.getCode())
        )).thenReturn(true);

        contentService.applyRiskReviewResult(1L, "PASS", null);

        verify(relationEventOutboxRepository, never()).save(anyLong(), eq("POST"), anyString());
    }

    @Test
    void rollback_shouldDispatchUpdateAfterCommit() {
        RLock lock = Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        ContentPostEntity post = ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .contentUuid("old-uuid")
                .visibility(1)
                .versionNum(5)
                .build();
        ContentHistoryEntity target = ContentHistoryEntity.builder()
                .postId(101L)
                .versionNum(2)
                .snapshotTitle("t")
                .snapshotContent("body")
                .build();

        when(contentRepository.findPostForUpdate(101L)).thenReturn(post);
        when(contentRepository.findHistoryVersion(101L, 2)).thenReturn(target);
        when(contentRepository.updatePostStatusAndContent(
                eq(101L), eq(2), eq(6), eq(true), eq("t"), any(), anyString(), any(), any(), eq(1)
        )).thenReturn(true);
        when(socialIdPort.nextId()).thenReturn(900L);
        when(socialIdPort.now()).thenReturn(1000L);

        contentService.rollback(101L, 11L, 2L);

        verify(contentEventOutboxPort).savePostUpdated(eq(101L), eq(11L), eq(6), eq(1000L));
        verify(contentEventOutboxPort).savePostSummaryGenerate(eq(101L), eq(11L), eq(6), eq(1000L));
        verify(contentCacheEvictPort).evictPost(101L);
    }

    @Test
    void syncDraft_shouldRejectStaleClientVersion() {
        when(contentRepository.findDraftForUpdate(101L)).thenReturn(ContentDraftEntity.builder()
                .draftId(101L)
                .userId(11L)
                .clientVersion(5L)
                .build());

        assertThrows(AppException.class, () -> contentService.syncDraft(101L, 11L, "t", "body", 4L, "device", null));
    }

    @Test
    void schedule_shouldReturnDuplicateWhenTokenExists() {
        when(contentRepository.findDraft(101L)).thenReturn(ContentDraftEntity.builder()
                .draftId(101L)
                .userId(11L)
                .title("title")
                .draftContent("body")
                .build());
        when(contentRepository.findScheduleByToken(anyString())).thenReturn(ContentScheduleEntity.builder()
                .taskId(301L)
                .status(0)
                .build());

        assertEquals("SCHEDULED_DUPLICATE", contentService.schedule(11L, 101L, 2000L, null).getStatus());
    }

    @Test
    void history_shouldReturnNoPermissionForOtherUser() {
        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder().postId(101L).userId(12L).build());

        ContentHistoryVO result = contentService.history(101L, 11L, 20, 0);

        assertEquals("NO_PERMISSION", result.getStatus());
        assertEquals(0, result.getVersions().size());
    }

    @Test
    void delete_shouldSoftDeleteAndDispatchDeleteAfterCommit() {
        RLock lock = Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(contentRepository.findPostForUpdate(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(1)
                .versionNum(3)
                .contentUuid("uuid-1")
                .build());
        when(socialIdPort.now()).thenReturn(1000L);
        when(contentRepository.softDeleteIfMatchStatusAndVersion(101L, 1, 3, 1000L)).thenReturn(true);

        cn.nexus.domain.social.model.valobj.OperationResultVO result = contentService.delete(11L, 101L);

        assertEquals("DELETED", result.getStatus());
        verify(postContentKvPort).delete("uuid-1");
        verify(contentEventOutboxPort).savePostDeleted(101L, 11L, 4, 1000L);
        verify(contentCacheEvictPort).evictPost(101L);
        verify(relationEventOutboxRepository, never()).save(anyLong(), eq("POST"), anyString());
    }

    @Test
    void delete_publishedPost_shouldWritePostCounterDecrementOutbox() {
        RLock lock = Mockito.mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(contentRepository.findPostForUpdate(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .status(2)
                .versionNum(3)
                .contentUuid("uuid-1")
                .build());
        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(700L);
        when(contentRepository.softDeleteIfMatchStatusAndVersion(101L, 2, 3, 1000L)).thenReturn(true);

        cn.nexus.domain.social.model.valobj.OperationResultVO result = contentService.delete(11L, 101L);

        assertEquals("DELETED", result.getStatus());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(relationEventOutboxRepository).save(eq(700L), eq("POST"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"sourceId\":11"));
        assertTrue(payload.contains("\"targetId\":101"));
        assertTrue(payload.contains("\"projectionKey\":\"post:101\""));
        assertTrue(payload.contains("\"projectionVersion\":4"));
        assertTrue(payload.contains("\"status\":\"UNPUBLISHED\""));
    }

    @Test
    void executeSchedule_shouldCancelTaskWhenDraftMissing() {
        when(contentRepository.findSchedule(301L)).thenReturn(ContentScheduleEntity.builder()
                .taskId(301L)
                .userId(11L)
                .postId(101L)
                .status(0)
                .retryCount(1)
                .build());
        when(contentRepository.findDraft(101L)).thenReturn(null);

        cn.nexus.domain.social.model.valobj.OperationResultVO result = contentService.executeSchedule(301L);

        assertEquals("DRAFT_NOT_FOUND", result.getStatus());
        verify(contentRepository).updateScheduleStatus(301L, 3, 1, "draft_not_found", 1, null, 0);
    }

    @Test
    void getPublishAttemptAudit_shouldHideOtherUsersAttempt() {
        when(contentPublishAttemptRepository.findByAttemptId(88L)).thenReturn(ContentPublishAttemptEntity.builder()
                .attemptId(88L)
                .userId(12L)
                .build());

        assertEquals(null, contentService.getPublishAttemptAudit(88L, 11L));
    }
}
