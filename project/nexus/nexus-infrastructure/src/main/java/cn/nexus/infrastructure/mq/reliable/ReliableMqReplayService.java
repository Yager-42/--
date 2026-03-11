package cn.nexus.infrastructure.mq.reliable;

import cn.nexus.infrastructure.dao.social.IReliableMqReplayRecordDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqReplayRecordPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * DLQ 失败记录与自动重放服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMqReplayService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRY_PENDING = "RETRY_PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FINAL_FAILED = "FINAL_FAILED";

    private final IReliableMqReplayRecordDao replayRecordDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void recordFailure(String consumerName,
                              String originalQueue,
                              String originalExchange,
                              String originalRoutingKey,
                              Message message,
                              String fallbackPayloadType,
                              String explicitEventId,
                              String lastError) {
        ReliableMqMessageSupport support = new ReliableMqMessageSupport(objectMapper);
        String payloadJson = support.payloadJson(message);
        String payloadType = support.payloadType(message, fallbackPayloadType);
        String eventId = explicitEventId;
        if (eventId == null || eventId.isBlank()) {
            eventId = support.extractEventId(payloadJson);
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("replay record eventId is blank, consumer=" + consumerName);
        }

        ReliableMqReplayRecordPO po = new ReliableMqReplayRecordPO();
        po.setEventId(eventId);
        po.setConsumerName(consumerName);
        po.setOriginalQueue(originalQueue);
        po.setOriginalExchange(originalExchange);
        po.setOriginalRoutingKey(originalRoutingKey);
        po.setPayloadType(payloadType);
        po.setPayloadJson(payloadJson);
        po.setStatus(STATUS_PENDING);
        po.setAttempt(0);
        po.setNextRetryAt(new Date());
        po.setLastError(lastError);
        replayRecordDao.insertIgnore(po);
    }

    public void replayReady(int limit) {
        ReliableMqMessageSupport support = new ReliableMqMessageSupport(objectMapper);
        List<ReliableMqReplayRecordPO> records = replayRecordDao.selectReady(new Date(), limit);
        for (ReliableMqReplayRecordPO record : records) {
            try {
                Object payload = support.fromPayload(record.getPayloadType(), record.getPayloadJson());
                rabbitTemplate.convertAndSend(record.getOriginalExchange(), record.getOriginalRoutingKey(), payload);
                replayRecordDao.markDone(record.getId());
            } catch (Exception e) {
                int nextAttempt = record.getAttempt() == null ? 1 : record.getAttempt() + 1;
                String nextStatus = nextAttempt >= ReliableMqPolicy.MAX_REPLAY_ATTEMPTS
                        ? STATUS_FINAL_FAILED : STATUS_RETRY_PENDING;
                Date nextRetryAt = STATUS_FINAL_FAILED.equals(nextStatus)
                        ? ReliableMqPolicy.nextReplayTime(ReliableMqPolicy.MAX_REPLAY_ATTEMPTS - 1)
                        : ReliableMqPolicy.nextReplayTime(nextAttempt - 1);
                replayRecordDao.markRetry(record.getId(), nextAttempt, nextRetryAt, shorten(e), nextStatus);
                log.warn("reliable mq replay failed eventId={} consumer={} queue={}",
                        record.getEventId(), record.getConsumerName(), record.getOriginalQueue(), e);
            }
        }
    }

    private String shorten(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
