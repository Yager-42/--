package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IReactionEventLogPort;
import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.ReactionEventLogMqConfig;
import cn.nexus.types.event.interaction.ReactionEventLogMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionEventLogConsumer {

    private static final String CONSUMER_NAME = "reaction-event-log";

    private final IReactionEventLogPort reactionEventLogPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = ReactionEventLogMqConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    public void onMessage(ReactionEventLogMessage event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()
                || event.getTargetType() == null || event.getTargetType().isBlank()
                || event.getTargetId() == null || event.getReactionType() == null || event.getReactionType().isBlank()
                || event.getUserId() == null || event.getDesiredState() == null || event.getDelta() == null) {
            throw new AmqpRejectAndDontRequeueException("reaction.event.log missing required fields");
        }
        String payloadJson = safeJson(event);
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, payloadJson)) {
            return;
        }
        try {
            String result = reactionEventLogPort.append(ReactionEventLogRecordVO.builder()
                    .eventId(event.getEventId())
                    .targetType(event.getTargetType())
                    .targetId(event.getTargetId())
                    .reactionType(event.getReactionType())
                    .userId(event.getUserId())
                    .desiredState(event.getDesiredState())
                    .delta(event.getDelta())
                    .eventTime(event.getEventTime())
                    .build());
            if (!"inserted".equals(result) && !"duplicate".equals(result)) {
                consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, "unsupported-result");
                throw new AmqpRejectAndDontRequeueException("reaction.event.log append returned unsupported result");
            }
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (AmqpRejectAndDontRequeueException e) {
            throw e;
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getClass().getSimpleName());
            log.error("reaction event log consume failed, eventId={}", event.getEventId(), e);
            throw new AmqpRejectAndDontRequeueException("reaction.event.log append failed", e);
        }
    }

    private String safeJson(ReactionEventLogMessage event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return null;
        }
    }
}
