package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.Mockito.verify;

class RiskImageScanDlqConsumerTest {

    @Test
    void onMessage_recordsDlqWithOriginalRiskImageScanRouteMetadata() throws Throwable {
        ReliableMqReplayService replayService = Mockito.mock(ReliableMqReplayService.class);
        RiskImageScanDlqConsumer consumer = new RiskImageScanDlqConsumer();
        Message message = new Message("{}".getBytes(), new MessageProperties());
        ProceedingJoinPoint joinPoint = ReliableDlqAspectTestSupport.joinPoint(consumer, "onMessage", message);

        ReliableDlqAspectTestSupport.aspect(replayService).around(
                joinPoint,
                ReliableDlqAspectTestSupport.annotation(RiskImageScanDlqConsumer.class, "onMessage"));

        verify(replayService).recordFailure(
                "RiskImageScanConsumer",
                RiskMqConfig.Q_IMAGE_SCAN,
                RiskMqConfig.EXCHANGE,
                RiskMqConfig.RK_IMAGE_SCAN,
                message,
                "cn.nexus.types.event.risk.ImageScanRequestedEvent",
                "",
                "risk image scan dead-lettered");
    }
}
