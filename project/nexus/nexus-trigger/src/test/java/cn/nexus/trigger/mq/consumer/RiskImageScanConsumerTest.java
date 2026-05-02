package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.service.risk.RiskAsyncService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskImageScanConsumerTest {

    @Test
    void onMessage_duplicateDoneDoesNotInvokeBusinessDependency() throws Throwable {
        Fixture fixture = new Fixture();
        ImageScanRequestedEvent event = validEvent("evt-image-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-image-1"),
                Mockito.eq("RiskImageScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.riskAsyncService, never()).applyLlmResult(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_startedSuccessInvokesBusinessDependencyAndMarksDone() throws Throwable {
        Fixture fixture = new Fixture();
        ImageScanRequestedEvent event = validEvent("evt-image-2");
        RiskLlmResultVO llmResult = RiskLlmResultVO.builder()
                .contentType("IMAGE")
                .result("PASS")
                .reasonCode("OK")
                .confidence(0.99D)
                .riskTags(List.of())
                .build();
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-image-2"),
                Mockito.eq("RiskImageScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        fixture.mockCacheMiss();
        fixture.mockBudgetAllowed();
        fixture.mockInflightLock();
        when(fixture.llmPort.scanImage(Mockito.eq("image.scan"), Mockito.eq("IMAGE_SCAN"),
                Mockito.eq(List.of("https://cdn.example/image.png")), Mockito.isNull()))
                .thenReturn(llmResult);

        fixture.invokeThroughAspect(event);

        verify(fixture.riskAsyncService).applyLlmResult(99L, llmResult);
        verify(fixture.consumerRecordService).markDone("evt-image-2", "RiskImageScanConsumer");
    }

    @Test
    void onMessage_businessFailureMarksFailAndRethrows() throws Throwable {
        Fixture fixture = new Fixture();
        ImageScanRequestedEvent event = validEvent("evt-image-3");
        RuntimeException failure = new RuntimeException("risk apply down");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-image-3"),
                Mockito.eq("RiskImageScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        fixture.mockCacheMiss();
        fixture.mockBudgetAllowed();
        fixture.mockInflightLock();
        when(fixture.llmPort.scanImage(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(RiskLlmResultVO.builder().contentType("IMAGE").result("PASS").riskTags(List.of()).build());
        Mockito.doThrow(failure).when(fixture.riskAsyncService).applyLlmResult(Mockito.eq(99L), Mockito.any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("evt-image-3",
                "RiskImageScanConsumer", "risk apply down");
    }

    @Test
    void onMessage_invalidEventIdThrowsBeforeBusinessDependency() {
        Fixture fixture = new Fixture();
        ImageScanRequestedEvent event = validEvent(" ");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.riskAsyncService, never()).applyLlmResult(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static ImageScanRequestedEvent validEvent(String eventId) {
        ImageScanRequestedEvent event = new ImageScanRequestedEvent();
        event.setEventId(eventId);
        event.setDecisionId(99L);
        event.setTaskId("task-99");
        event.setImageUrl("https://cdn.example/image.png");
        return event;
    }

    private static final class Fixture {
        private final RedissonClient redissonClient = Mockito.mock(RedissonClient.class);
        private final IMediaStoragePort mediaStoragePort = Mockito.mock(IMediaStoragePort.class);
        private final IRiskLlmPort llmPort = Mockito.mock(IRiskLlmPort.class);
        private final RiskAsyncService riskAsyncService = Mockito.mock(RiskAsyncService.class);
        private final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final RiskImageScanConsumer consumer = new RiskImageScanConsumer(
                redissonClient,
                mediaStoragePort,
                llmPort,
                riskAsyncService,
                new ObjectMapper(),
                rabbitTemplate);

        private void invokeThroughAspect(ImageScanRequestedEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onMessage", "event", event, () -> consumer.onMessage(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            RiskImageScanConsumer.class, "onMessage", ImageScanRequestedEvent.class));
        }

        private void mockCacheMiss() {
            @SuppressWarnings("unchecked")
            RBucket<String> bucket = Mockito.mock(RBucket.class);
            when(redissonClient.<String>getBucket(Mockito.startsWith("risk:llm:cache:"))).thenReturn(bucket);
            when(bucket.get()).thenReturn(null);
        }

        private void mockBudgetAllowed() {
            RAtomicLong budget = Mockito.mock(RAtomicLong.class);
            when(redissonClient.getAtomicLong(Mockito.startsWith("risk:llm:budget:"))).thenReturn(budget);
            when(budget.incrementAndGet()).thenReturn(1L);
        }

        private void mockInflightLock() throws InterruptedException {
            RLock lock = Mockito.mock(RLock.class);
            when(redissonClient.getLock(Mockito.startsWith("risk:llm:inflight:"))).thenReturn(lock);
            when(lock.tryLock(Mockito.eq(200L), Mockito.anyLong(), Mockito.eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        }
    }
}
