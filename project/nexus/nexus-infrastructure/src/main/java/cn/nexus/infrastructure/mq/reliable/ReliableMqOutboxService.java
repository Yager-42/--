package cn.nexus.infrastructure.mq.reliable;

import cn.nexus.infrastructure.dao.social.IReliableMqOutboxDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqOutboxPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 *
 * @author {$authorName}
 * @since 2026-03-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMqOutboxService {

    /** Outbox 状态：待发送。 */
    public static final String STATUS_PENDING = "PENDING";
    /** Outbox 状态：待重试发送。 */
    public static final String STATUS_RETRY_PENDING = "RETRY_PENDING";
    /** Outbox 状态：已发送。 */
    public static final String STATUS_SENT = "SENT";
    /** Outbox 状态：最终失败（不再重试）。 */
    public static final String STATUS_FINAL_FAILED = "FINAL_FAILED";

    private final IReliableMqOutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        // Reliable MQ 的 payload 可能包含 BaseEvent.occurredAt(Instant)，默认 ObjectMapper 无法反序列化会导致 publishReady 空转。
        objectMapper.findAndRegisterModules();
    }

    /**
     * 保存一条待发送消息到 Outbox（不直接发送）。
     *
     * @param eventId 事件 ID（幂等键） {@link String}
     * @param exchangeName Exchange 名称 {@link String}
     * @param routingKey RoutingKey {@link String}
     * @param payload 消息体 {@link Object}
     */
    public void save(String eventId, String exchangeName, String routingKey, Object payload) {
        save(eventId, exchangeName, routingKey, payload, java.util.Map.of());
    }

    /**
     * 保存一条待发送消息到 Outbox（可携带 headers）。
     *
     * @param eventId 事件 ID（幂等键） {@link String}
     * @param exchangeName Exchange 名称 {@link String}
     * @param routingKey RoutingKey {@link String}
     * @param payload 消息体 {@link Object}
     * @param headers headers（可为空） {@link Map}
     */
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

    /**
     * 发布所有“已到重试时间”的 Outbox 记录。
     *
     * <p>这通常由定时任务触发：批量拉取 ready 记录，逐条投递到 MQ，并根据结果更新状态与下一次重试时间。</p>
     *
     * @param limit 单次发布上限 {@code int}
     */
    public void publishReady(int limit) {
        ReliableMqMessageSupport support = new ReliableMqMessageSupport(objectMapper);
        List<ReliableMqOutboxPO> records = outboxDao.selectReady(new Date(), limit);
        for (ReliableMqOutboxPO record : records) {
            try {
                // 1. 反序列化 payload 与 headers。
                Object payload = support.fromPayload(record.getPayloadType(), record.getPayloadJson());
                Map<String, Object> headers = objectMapper.readValue(record.getHeadersJson() == null ? "{}" : record.getHeadersJson(), Map.class);
                // 2. 投递到 MQ，并把 headers 写入消息属性。
                rabbitTemplate.convertAndSend(record.getExchangeName(), record.getRoutingKey(), payload, message -> {
                    if (headers != null) {
                        headers.forEach((key, value) -> message.getMessageProperties().setHeader(key, value));
                    }
                    return message;
                });
                // 3. 投递成功：标记 SENT。
                outboxDao.markSent(record.getId());
            } catch (Exception e) {
                // 4. 投递失败：按策略计算下一次重试时间与状态。
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
