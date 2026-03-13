package cn.nexus.infrastructure.adapter.user.port;

import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import cn.nexus.infrastructure.dao.user.IUserEventOutboxDao;
import cn.nexus.infrastructure.dao.user.po.UserEventOutboxPO;
import cn.nexus.types.event.UserNicknameChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 用户域事件 Outbox 端口实现：MySQL 落库 + RabbitMQ 投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventOutboxPort implements IUserEventOutboxPort {

    private static final String EXCHANGE = "social.feed";

    private static final String EVENT_TYPE_NICKNAME_CHANGED = "user.nickname_changed";
    private static final String ROUTING_KEY_NICKNAME_CHANGED = "user.nickname_changed";

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_DONE = "DONE";

    private final IUserEventOutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void saveNicknameChanged(Long userId, Long tsMs) {
        if (userId == null || tsMs == null) {
            return;
        }
        String payload = toPayload(userId, tsMs);
        if (payload == null) {
            return;
        }

        UserEventOutboxPO po = new UserEventOutboxPO();
        po.setEventType(EVENT_TYPE_NICKNAME_CHANGED);
        po.setFingerprint(fingerprint(EVENT_TYPE_NICKNAME_CHANGED, userId, tsMs));
        po.setPayload(payload);
        po.setStatus(STATUS_NEW);
        po.setRetryCount(0);
        outboxDao.insertIgnore(po);
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
        return outboxDao.deleteOlderThan(beforeTime, STATUS_DONE);
    }

    private void publishByStatus(String status, int limit) {
        List<UserEventOutboxPO> list = outboxDao.selectByStatus(status, limit);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (UserEventOutboxPO po : list) {
            if (po == null || po.getId() == null) {
                continue;
            }
            try {
                publishOne(po);
                outboxDao.markDone(po.getId());
            } catch (Exception e) {
                outboxDao.markFail(po.getId());
                log.warn("user outbox publish failed id={} type={} fp={}", po.getId(), po.getEventType(), po.getFingerprint(), e);
            }
        }
    }

    private void publishOne(UserEventOutboxPO po) throws Exception {
        if (EVENT_TYPE_NICKNAME_CHANGED.equals(po.getEventType())) {
            UserNicknameChangedEvent event = objectMapper.readValue(po.getPayload(), UserNicknameChangedEvent.class);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_NICKNAME_CHANGED, event);
            log.info("event=user.outbox.published type={} id={} userId={}", po.getEventType(), po.getId(), event.getUserId());
            return;
        }
        throw new IllegalArgumentException("unsupported eventType=" + po.getEventType());
    }

    private String fingerprint(String eventType, Long userId, Long tsMs) {
        return eventType + ":" + userId + ":" + tsMs;
    }

    private String toPayload(Long userId, Long tsMs) {
        try {
            // payload 最小化：只发 {userId, tsMs}，消费者回表读 nickname
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "userId", userId,
                    "tsMs", tsMs
            ));
        } catch (Exception e) {
            log.warn("user outbox payload serialize failed userId={} tsMs={}", userId, tsMs, e);
            return null;
        }
    }
}

