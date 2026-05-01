package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RelationBlockDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = RelationMqConfig.DLQ_BLOCK)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "RelationCounterProjectConsumer",
                RelationMqConfig.Q_BLOCK,
                RelationMqConfig.EXCHANGE,
                RelationMqConfig.RK_BLOCK,
                "cn.nexus.types.event.relation.RelationCounterProjectEvent",
                null,
                "relation block projection dead-lettered"
        );
    }
}
