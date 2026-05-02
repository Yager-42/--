package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.service.risk.RiskAsyncService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.producer.RiskScanCompletedProducer;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskLlmScanConsumerTest {

    @Test
    void onMessage_duplicateDoneDoesNotInvokeBusinessDependency() throws Throwable {
        Fixture fixture = new Fixture();
        LlmScanRequestedEvent event = validEvent("evt-llm-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-llm-1"),
                Mockito.eq("RiskLlmScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.riskAsyncService, never()).applyLlmResult(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_startedSuccessInvokesBusinessDependencyPublishesCompletedAndMarksDone() throws Throwable {
        Fixture fixture = new Fixture();
        LlmScanRequestedEvent event = validEvent("evt-llm-2");
        RiskLlmResultVO llmResult = RiskLlmResultVO.builder()
                .contentType("TEXT")
                .result("PASS")
                .reasonCode("OK")
                .confidence(0.98D)
                .riskTags(List.of("clean"))
                .promptVersion(7L)
                .model("risk-model")
                .build();
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-llm-2"),
                Mockito.eq("RiskLlmScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        fixture.mockCacheMiss();
        fixture.mockBudgetAllowed();
        fixture.mockInflightLock();
        when(fixture.llmPort.scanText(Mockito.eq("post.publish"), Mockito.eq("CONTENT_CREATE"),
                Mockito.eq("safe text"), Mockito.isNull()))
                .thenReturn(llmResult);

        fixture.invokeThroughAspect(event);

        verify(fixture.riskAsyncService).applyLlmResult(199L, llmResult);
        verify(fixture.riskScanCompletedProducer).publish(argThat(completed ->
                completed != null
                        && "evt-llm-2:completed".equals(completed.getEventId())
                        && Long.valueOf(199L).equals(completed.getDecisionId())
                        && "task-199".equals(completed.getTaskId())
                        && "TEXT".equals(completed.getContentType())
                        && "PASS".equals(completed.getResult())
                        && "OK".equals(completed.getReasonCode())
                        && Double.valueOf(0.98D).equals(completed.getConfidence())
                        && List.of("clean").equals(completed.getRiskTags())
                        && Long.valueOf(7L).equals(completed.getPromptVersion())
                        && "risk-model".equals(completed.getModel())));
        verify(fixture.consumerRecordService).markDone("evt-llm-2", "RiskLlmScanConsumer");
    }

    @Test
    void onMessage_businessFailureMarksFailAndRethrows() throws Throwable {
        Fixture fixture = new Fixture();
        LlmScanRequestedEvent event = validEvent("evt-llm-3");
        RuntimeException failure = new RuntimeException("risk apply down");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-llm-3"),
                Mockito.eq("RiskLlmScanConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        fixture.mockCacheMiss();
        fixture.mockBudgetAllowed();
        fixture.mockInflightLock();
        when(fixture.llmPort.scanText(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(RiskLlmResultVO.builder().contentType("TEXT").result("PASS").riskTags(List.of()).build());
        Mockito.doThrow(failure).when(fixture.riskAsyncService).applyLlmResult(Mockito.eq(199L), Mockito.any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("evt-llm-3",
                "RiskLlmScanConsumer", "risk apply down");
    }

    @Test
    void onMessage_invalidEventIdThrowsBeforeBusinessDependency() {
        Fixture fixture = new Fixture();
        LlmScanRequestedEvent event = validEvent(" ");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.riskAsyncService, never()).applyLlmResult(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static LlmScanRequestedEvent validEvent(String eventId) {
        LlmScanRequestedEvent event = new LlmScanRequestedEvent();
        event.setEventId(eventId);
        event.setDecisionId(199L);
        event.setTaskId("task-199");
        event.setScenario("post.publish");
        event.setActionType("CONTENT_CREATE");
        event.setContentType("TEXT");
        event.setContentText("safe text");
        return event;
    }

    private static final class Fixture {
        private final RedissonClient redissonClient = Mockito.mock(RedissonClient.class);
        private final IMediaStoragePort mediaStoragePort = Mockito.mock(IMediaStoragePort.class);
        private final IRiskLlmPort llmPort = Mockito.mock(IRiskLlmPort.class);
        private final RiskAsyncService riskAsyncService = Mockito.mock(RiskAsyncService.class);
        private final RiskScanCompletedProducer riskScanCompletedProducer = Mockito.mock(RiskScanCompletedProducer.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final RiskLlmScanConsumer consumer = new RiskLlmScanConsumer(
                redissonClient,
                mediaStoragePort,
                llmPort,
                riskAsyncService,
                new ObjectMapper(),
                riskScanCompletedProducer);

        private void invokeThroughAspect(LlmScanRequestedEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onMessage", "event", event, () -> consumer.onMessage(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            RiskLlmScanConsumer.class, "onMessage", LlmScanRequestedEvent.class));
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
