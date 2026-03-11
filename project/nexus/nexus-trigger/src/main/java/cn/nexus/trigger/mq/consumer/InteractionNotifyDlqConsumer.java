package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InteractionNotifyDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = InteractionNotifyMqConfig.DLQ_INTERACTION_NOTIFY)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "InteractionNotifyConsumer",
                InteractionNotifyMqConfig.Q_INTERACTION_NOTIFY,
                "social.interaction",
                InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY,
                "cn.nexus.types.event.interaction.InteractionNotifyEvent",
                null,
                "interaction notify dead-lettered"
        );
    }
}
