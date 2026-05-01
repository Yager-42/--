package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.model.valobj.RelationEventOutboxVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关系事件 Outbox 发布任务：把库里的待发事件稳定推到 MQ，并清理历史完成记录。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-08
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelationEventOutboxPublishJob {

    private final IRelationEventOutboxRepository outboxRepository;
    private final IRelationEventPort relationEventPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行 publishPending 逻辑。
     *
     */
    @Scheduled(fixedDelay = 60000)
    public void publishPending() {
        // NEW 和 FAIL 分两轮扫，目的是让“首次发送”和“补偿重试”都走同一套发布逻辑，
        // 但调度上又保持可观察、可限流。
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

    /**
     * 执行 cleanDone 逻辑。
     *
     */
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
            RelationOutboxPayload event = objectMapper.readValue(item.getPayload(), RelationOutboxPayload.class);
            if (!relationEventPort.publishCounterProjection(event.eventId, "FOLLOW", event.sourceId, event.targetId, event.status)) {
                throw new IllegalStateException("publish relation follow projection failed");
            }
            return;
        }
        if ("BLOCK".equals(item.getEventType())) {
            RelationOutboxPayload event = objectMapper.readValue(item.getPayload(), RelationOutboxPayload.class);
            if (!relationEventPort.publishCounterProjection(event.eventId, "BLOCK", event.sourceId, event.targetId, null)) {
                throw new IllegalStateException("publish relation block projection failed");
            }
            return;
        }
        throw new IllegalArgumentException("unsupported eventType=" + item.getEventType());
    }

    private Date nextRetryTime(Integer currentRetryCount) {
        int cur = currentRetryCount == null ? 0 : Math.max(0, currentRetryCount);
        long delayMs = 60_000L * (cur + 1L);
        return new Date(System.currentTimeMillis() + delayMs);
    }

    private static final class RelationOutboxPayload {
        public Long eventId;
        public Long sourceId;
        public Long targetId;
        public String status;
    }
}
