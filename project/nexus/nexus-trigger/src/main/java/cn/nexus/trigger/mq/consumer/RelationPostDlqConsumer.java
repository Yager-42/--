package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RelationPostDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = RelationMqConfig.DLQ_POST)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "RelationCounterProjectConsumer",
                RelationMqConfig.Q_POST,
                RelationMqConfig.EXCHANGE,
                RelationMqConfig.RK_POST,
                "cn.nexus.types.event.relation.RelationCounterProjectEvent",
                null,
                "relation post projection dead-lettered"
        );
    }
}
