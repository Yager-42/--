package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import cn.nexus.infrastructure.dao.social.IContentEventOutboxDao;
import cn.nexus.infrastructure.dao.social.po.ContentEventOutboxPO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostSummaryGenerateEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 内容域事件 Outbox 端口实现：MySQL 落库 + RabbitMQ 投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventOutboxPort implements IContentEventOutboxPort {

    private static final String EXCHANGE = "social.feed";

    private static final String EVENT_TYPE_PUBLISHED = "post.published";
    private static final String EVENT_TYPE_UPDATED = "post.updated";
    private static final String EVENT_TYPE_DELETED = "post.deleted";
    private static final String EVENT_TYPE_SUMMARY_GENERATE = "post.summary.generate";

    private static final String ROUTING_KEY_PUBLISHED = "post.published";
    private static final String ROUTING_KEY_UPDATED = "post.updated";
    private static final String ROUTING_KEY_DELETED = "post.deleted";
    private static final String ROUTING_KEY_SUMMARY_GENERATE = "post.summary.generate";

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_SENT = "SENT";

    private final IContentEventOutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void savePostPublished(Long postId, Long userId, Integer versionNum, Long tsMs) {
        if (postId == null || userId == null || tsMs == null) {
            return;
        }
        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(postId);
        event.setAuthorId(userId);
        event.setPublishTimeMs(tsMs);

        saveEvent(EVENT_TYPE_PUBLISHED, eventId(EVENT_TYPE_PUBLISHED, postId, versionNum), toPayload(event), tsMs);
    }

    @Override
    public void savePostUpdated(Long postId, Long operatorId, Integer versionNum, Long tsMs) {
        if (postId == null || operatorId == null || tsMs == null) {
            return;
        }
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(tsMs);

        saveEvent(EVENT_TYPE_UPDATED, eventId(EVENT_TYPE_UPDATED, postId, versionNum), toPayload(event), tsMs);
    }

    @Override
    public void savePostDeleted(Long postId, Long operatorId, Integer versionNum, Long tsMs) {
        if (postId == null || operatorId == null || tsMs == null) {
            return;
        }
        PostDeletedEvent event = new PostDeletedEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(tsMs);

        saveEvent(EVENT_TYPE_DELETED, eventId(EVENT_TYPE_DELETED, postId, versionNum), toPayload(event), tsMs);
    }

    @Override
    public void savePostSummaryGenerate(Long postId, Long operatorId, Integer versionNum, Long tsMs) {
        if (postId == null || tsMs == null) {
            return;
        }
        PostSummaryGenerateEvent event = new PostSummaryGenerateEvent();
        event.setPostId(postId);
        event.setOperatorId(operatorId);
        event.setTsMs(tsMs);

        saveEvent(EVENT_TYPE_SUMMARY_GENERATE, eventId(EVENT_TYPE_SUMMARY_GENERATE, postId, versionNum), toPayload(event), tsMs);
    }

    @Override
    public void tryPublishPending() {
        publishByStatus(STATUS_NEW, 100);
        publishByStatus(STATUS_FAIL, 100);
    }

    @Override
    public int cleanDoneBefore(Date beforeTime) {
        if (beforeTime == null) {
            return 0;
        }
        return outboxDao.deleteOlderThan(beforeTime, STATUS_SENT);
    }

    private void saveEvent(String eventType, String eventId, String payload, Long tsMs) {
        if (eventType == null || eventId == null || payload == null || tsMs == null) {
            return;
        }
        ContentEventOutboxPO po = new ContentEventOutboxPO();
        po.setEventId(eventId);
        po.setEventType(eventType);
        po.setPayloadJson(payload);
        po.setStatus(STATUS_NEW);
        po.setRetryCount(0);
        po.setNextRetryTime(new Date(tsMs));
        outboxDao.insertIgnore(po);
    }

    private void publishByStatus(String status, int limit) {
        List<ContentEventOutboxPO> list = outboxDao.selectByStatus(status, new Date(), limit);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ContentEventOutboxPO po : list) {
            if (po == null || po.getEventId() == null) {
                continue;
            }
            try {
                publishOne(po);
                outboxDao.markSent(po.getEventId());
            } catch (Exception e) {
                outboxDao.markFail(po.getEventId(), nextRetryTime(po.getRetryCount()));
                log.warn("content outbox publish failed eventId={} type={}", po.getEventId(), po.getEventType(), e);
            }
        }
    }

    private void publishOne(ContentEventOutboxPO po) throws Exception {
        if (EVENT_TYPE_PUBLISHED.equals(po.getEventType())) {
            PostPublishedEvent event = objectMapper.readValue(po.getPayloadJson(), PostPublishedEvent.class);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_PUBLISHED, event);
            log.info("event=content.outbox.published type={} eventId={} postId={}", po.getEventType(), po.getEventId(), event.getPostId());
            return;
        }
        if (EVENT_TYPE_UPDATED.equals(po.getEventType())) {
            PostUpdatedEvent event = objectMapper.readValue(po.getPayloadJson(), PostUpdatedEvent.class);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_UPDATED, event);
            log.info("event=content.outbox.published type={} eventId={} postId={}", po.getEventType(), po.getEventId(), event.getPostId());
            return;
        }
        if (EVENT_TYPE_DELETED.equals(po.getEventType())) {
            PostDeletedEvent event = objectMapper.readValue(po.getPayloadJson(), PostDeletedEvent.class);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_DELETED, event);
            log.info("event=content.outbox.published type={} eventId={} postId={}", po.getEventType(), po.getEventId(), event.getPostId());
            return;
        }
        if (EVENT_TYPE_SUMMARY_GENERATE.equals(po.getEventType())) {
            PostSummaryGenerateEvent event = objectMapper.readValue(po.getPayloadJson(), PostSummaryGenerateEvent.class);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_SUMMARY_GENERATE, event);
            log.info("event=content.outbox.published type={} eventId={} postId={}", po.getEventType(), po.getEventId(), event.getPostId());
            return;
        }
        throw new IllegalArgumentException("unsupported eventType=" + po.getEventType());
    }

    private Date nextRetryTime(Integer currentRetryCount) {
        int cur = currentRetryCount == null ? 0 : Math.max(0, currentRetryCount);
        // 简单退避：每失败一次延后 60s（不做复杂策略，避免引入新的特殊情况）。
        long delayMs = 60_000L * (cur + 1L);
        return new Date(System.currentTimeMillis() + delayMs);
    }

    private String eventId(String eventType, Long postId, Integer versionNum) {
        String v = versionNum == null ? "0" : String.valueOf(versionNum);
        return eventType + ":" + postId + ":" + v;
    }

    private String toPayload(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("content outbox payload serialize failed event={}", event == null ? "null" : event.getClass().getSimpleName(), e);
            return null;
        }
    }
}
