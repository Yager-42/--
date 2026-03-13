package cn.nexus.trigger.mq.support;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * DLQ 记录助手：每个 DLQ consumer 只负责告诉它“这条失败消息原本属于谁”。
 */
@Component
@RequiredArgsConstructor
public class ReliableMqDlqRecorder {

    private final ReliableMqReplayService reliableMqReplayService;

    public void record(Message message,
                       String consumerName,
                       String originalQueue,
                       String originalExchange,
                       String originalRoutingKey,
                       String fallbackPayloadType,
                       String explicitEventId,
                       String lastError) {
        reliableMqReplayService.recordFailure(
                consumerName,
                originalQueue,
                originalExchange,
                originalRoutingKey,
                message,
                fallbackPayloadType,
                explicitEventId,
                lastError
        );
    }
}
