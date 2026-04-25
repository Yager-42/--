package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RelationFollowDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = RelationMqConfig.DLQ_FOLLOW)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "RelationCounterProjectConsumer",
                RelationMqConfig.Q_FOLLOW,
                RelationMqConfig.EXCHANGE,
                RelationMqConfig.RK_FOLLOW,
                "cn.nexus.types.event.relation.RelationCounterProjectEvent",
                null,
                "relation follow projection dead-lettered"
        );
    }
}
