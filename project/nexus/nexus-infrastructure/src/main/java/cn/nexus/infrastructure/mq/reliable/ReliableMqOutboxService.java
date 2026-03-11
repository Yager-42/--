package cn.nexus.infrastructure.mq.reliable;

import cn.nexus.infrastructure.dao.social.IReliableMqOutboxDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqOutboxPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 通用 RabbitMQ Outbox：
 * 1. 发送端只落库，不直接裸发。
 * 2. 定时任务统一扫描并重发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMqOutboxService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRY_PENDING = "RETRY_PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FINAL_FAILED = "FINAL_FAILED";

    private final IReliableMqOutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void save(String eventId, String exchangeName, String routingKey, Object payload) {
        save(eventId, exchangeName, routingKey, payload, java.util.Map.of());
    }

    public void save(String eventId, String exchangeName, String routingKey, Object payload, Map<String, Object> headers) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is blank");
        }
        ReliableMqMessageSupport support = new ReliableMqMessageSupport(objectMapper);
        ReliableMqOutboxPO po = new ReliableMqOutboxPO();
        po.setEventId(eventId);
        po.setExchangeName(exchangeName == null ? "" : exchangeName);
        po.setRoutingKey(routingKey == null ? "" : routingKey);
        po.setPayloadType(support.payloadType(payload));
        po.setPayloadJson(support.toPayloadJson(payload));
        po.setHeadersJson(support.toPayloadJson(headers == null ? java.util.Map.of() : headers));
        po.setStatus(STATUS_PENDING);
        po.setRetryCount(0);
        po.setNextRetryAt(new Date());
        outboxDao.insertIgnore(po);
    }

    public void publishReady(int limit) {
        ReliableMqMessageSupport support = new ReliableMqMessageSupport(objectMapper);
        List<ReliableMqOutboxPO> records = outboxDao.selectReady(new Date(), limit);
        for (ReliableMqOutboxPO record : records) {
            try {
                Object payload = support.fromPayload(record.getPayloadType(), record.getPayloadJson());
                Map<String, Object> headers = objectMapper.readValue(record.getHeadersJson() == null ? "{}" : record.getHeadersJson(), Map.class);
                rabbitTemplate.convertAndSend(record.getExchangeName(), record.getRoutingKey(), payload, message -> {
                    if (headers != null) {
                        headers.forEach((key, value) -> message.getMessageProperties().setHeader(key, value));
                    }
                    return message;
                });
                outboxDao.markSent(record.getId());
            } catch (Exception e) {
                int nextAttempt = record.getRetryCount() == null ? 1 : record.getRetryCount() + 1;
                String nextStatus = nextAttempt >= ReliableMqPolicy.MAX_REPLAY_ATTEMPTS
                        ? STATUS_FINAL_FAILED : STATUS_RETRY_PENDING;
                Date nextRetryAt = STATUS_FINAL_FAILED.equals(nextStatus)
                        ? ReliableMqPolicy.nextReplayTime(ReliableMqPolicy.MAX_REPLAY_ATTEMPTS - 1)
                        : ReliableMqPolicy.nextReplayTime(nextAttempt - 1);
                outboxDao.markRetry(record.getId(), nextAttempt, nextRetryAt, shorten(e), nextStatus);
                log.warn("reliable mq outbox publish failed eventId={} exchange={} routingKey={}",
                        record.getEventId(), record.getExchangeName(), record.getRoutingKey(), e);
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
