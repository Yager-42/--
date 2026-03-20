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
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventOutboxPort implements IUserEventOutboxPort {

    /**
     * 用户事件交换机。
     */
    private static final String EXCHANGE = "social.feed";

    /**
     * 昵称变更事件类型。
     */
    private static final String EVENT_TYPE_NICKNAME_CHANGED = "user.nickname_changed";

    /**
     * 昵称变更路由键。
     */
    private static final String ROUTING_KEY_NICKNAME_CHANGED = "user.nickname_changed";

    /**
     * 新建状态。
     */
    private static final String STATUS_NEW = "NEW";

    /**
     * 投递失败状态。
     */
    private static final String STATUS_FAIL = "FAIL";

    /**
     * 投递完成状态。
     */
    private static final String STATUS_DONE = "DONE";

    private final IUserEventOutboxDao outboxDao;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存昵称变更事件到 Outbox。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param tsMs 事件时间戳，类型：{@link Long}
     */
    @Override
    public void saveNicknameChanged(Long userId, Long tsMs) {
        if (userId == null || tsMs == null) {
            return;
        }
        String payload = toPayload(userId, tsMs);
        if (payload == null) {
            return;
        }

        // 指纹把 `eventType + userId + tsMs` 锁死，重复提交时数据库自己去重。
        UserEventOutboxPO po = new UserEventOutboxPO();
        po.setEventType(EVENT_TYPE_NICKNAME_CHANGED);
        po.setFingerprint(fingerprint(EVENT_TYPE_NICKNAME_CHANGED, userId, tsMs));
        po.setPayload(payload);
        po.setStatus(STATUS_NEW);
        po.setRetryCount(0);
        outboxDao.insertIgnore(po);
    }

    /**
     * 尝试发布待发送事件。
     *
     * <p>先发 `NEW`，再补发 `FAIL`，避免把同一轮失败重试拖成无限循环。</p>
     */
    @Override
    public void tryPublishPending() {
        publishByStatus(STATUS_NEW, 100);
        publishByStatus(STATUS_FAIL, 100);
    }

    /**
     * 清理指定时间之前的已完成事件。
     *
     * @param beforeTime 清理截止时间，类型：{@link Date}
     * @return 删除行数，类型：{@code int}
     */
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
                // 发布成功才标记 DONE，避免“库里还是 NEW/FAIL，消息却已经出去了”的状态漂移。
                publishOne(po);
                outboxDao.markDone(po.getId());
            } catch (Exception e) {
                // 失败只打标，不在这里递归重试，重试节奏交给定时任务控制。
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
            // Payload 最小化：只发 `userId + tsMs`，消费者回表拿最新昵称，避免消息体自带旧值。
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
