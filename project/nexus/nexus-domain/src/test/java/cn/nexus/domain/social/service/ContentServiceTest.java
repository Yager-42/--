package cn.nexus.domain.social.service;

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
import cn.nexus.domain.social.model.entity.ContentHistoryEntity;
import cn.nexus.domain.social.model.entity.ContentPublishAttemptEntity;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptRiskStatusEnumVO;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptStatusEnumVO;
import cn.nexus.domain.social.model.valobj.ContentPublishAttemptTranscodeStatusEnumVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private IContentEventOutboxPort contentEventOutboxPort;
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
                contentEventOutboxPort,
                contentCacheEvictPort,
                redissonClient
        );
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
}
