package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.model.valobj.RelationEventOutboxVO;
import cn.nexus.infrastructure.adapter.social.port.RelationBlockEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFollowEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关系事件 Outbox 发布/清理任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelationEventOutboxPublishJob {

    private final IRelationEventOutboxRepository outboxRepository;
    private final IRelationEventPort relationEventPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 60000)
    public void publishPending() {
        List<RelationEventOutboxVO> list = outboxRepository.fetchPending("NEW", new Date(), 100);
        for (RelationEventOutboxVO item : list) {
            try {
                publish(item);
                outboxRepository.markSent(item.getEventId());
            } catch (Exception e) {
                log.warn("relation outbox publish failed, eventId={}, type={}", item.getEventId(), item.getEventType(), e);
                outboxRepository.markFail(item.getEventId(), nextRetryTime(item.getRetryCount()));
            }
        }

        List<RelationEventOutboxVO> failed = outboxRepository.fetchPending("FAIL", new Date(), 100);
        for (RelationEventOutboxVO item : failed) {
            try {
                publish(item);
                outboxRepository.markSent(item.getEventId());
            } catch (Exception e) {
                log.warn("relation outbox republish failed, eventId={}, type={}", item.getEventId(), item.getEventType(), e);
                outboxRepository.markFail(item.getEventId(), nextRetryTime(item.getRetryCount()));
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDone() {
        long sevenDays = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int deleted = outboxRepository.cleanSentBefore(new Date(sevenDays));
        log.info("event=relation.outbox.clean_done rows={}", deleted);
    }

    private void publish(RelationEventOutboxVO item) throws Exception {
        if (item == null || item.getEventType() == null || item.getPayload() == null) {
            return;
        }
        if ("FOLLOW".equals(item.getEventType())) {
            RelationFollowEvent event = objectMapper.readValue(item.getPayload(), RelationFollowEvent.class);
            relationEventPort.onFollow(event.eventId(), event.sourceId(), event.targetId(), event.status());
            return;
        }
        if ("BLOCK".equals(item.getEventType())) {
            RelationBlockEvent event = objectMapper.readValue(item.getPayload(), RelationBlockEvent.class);
            relationEventPort.onBlock(event.eventId(), event.sourceId(), event.targetId());
            return;
        }
        throw new IllegalArgumentException("unsupported eventType=" + item.getEventType());
    }

    private Date nextRetryTime(Integer currentRetryCount) {
        int cur = currentRetryCount == null ? 0 : Math.max(0, currentRetryCount);
        long delayMs = 60_000L * (cur + 1L);
        return new Date(System.currentTimeMillis() + delayMs);
    }
}
