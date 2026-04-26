package cn.nexus.infrastructure.mq.reliable;

import cn.nexus.infrastructure.dao.social.IReliableMqConsumerRecordDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqConsumerRecordPO;
import java.time.Duration;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通用消费幂等记录：
 * 1. 同一 eventId + consumerName 只允许一个成功副作用。
 * 2. 失败后允许 replay 重新进入。
 *
 * @author {$authorName}
 * @since 2026-03-11
 */
@Component
@RequiredArgsConstructor
public class ReliableMqConsumerRecordService {

    /** 消费状态：处理中。 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 消费状态：已完成。 */
    public static final String STATUS_DONE = "DONE";
    /** 消费状态：失败（允许重放）。 */
    public static final String STATUS_FAIL = "FAIL";
    private static final long PROCESSING_STALE_MILLIS = Duration.ofMinutes(5).toMillis();

    private final IReliableMqConsumerRecordDao consumerRecordDao;

    public enum StartResult {
        STARTED,
        DUPLICATE_DONE,
        IN_PROGRESS,
        INVALID
    }

    /**
     * 尝试开始消费（幂等入口）。
     *
     * <p>返回 {@code true} 表示“允许本次消费产生副作用”；返回 {@code false} 表示“已被幂等拦截”。</p>
     *
     * @param eventId 事件 ID {@link String}
     * @param consumerName 消费者名称 {@link String}
     * @param payloadJson 消息 payload（用于排查，可为空） {@link String}
     * @return 是否允许开始消费 {@code boolean}
     */
    public boolean start(String eventId, String consumerName, String payloadJson) {
        return startManual(eventId, consumerName, payloadJson) == StartResult.STARTED;
    }

    public StartResult startManual(String eventId, String consumerName, String payloadJson) {
        if (eventId == null || eventId.isBlank() || consumerName == null || consumerName.isBlank()) {
            return StartResult.INVALID;
        }
        ReliableMqConsumerRecordPO po = new ReliableMqConsumerRecordPO();
        po.setEventId(eventId);
        po.setConsumerName(consumerName);
        po.setPayloadJson(payloadJson);
        po.setStatus(STATUS_PROCESSING);
        if (consumerRecordDao.insertIgnore(po) > 0) {
            return StartResult.STARTED;
        }
        // 已存在记录：根据状态决定是否允许重放。
        ReliableMqConsumerRecordPO existed = consumerRecordDao.selectOne(eventId, consumerName);
        if (existed == null) {
            return StartResult.IN_PROGRESS;
        }
        if (STATUS_DONE.equals(existed.getStatus())) {
            return StartResult.DUPLICATE_DONE;
        }
        if (STATUS_PROCESSING.equals(existed.getStatus()) && !isStale(existed.getUpdateTime())) {
            return StartResult.IN_PROGRESS;
        }
        // FAIL/PROCESSING -> PROCESSING：允许 broker redelivery 恢复崩溃窗口。
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_PROCESSING, null);
        return StartResult.STARTED;
    }

    /**
     * 标记消费完成。
     *
     * @param eventId 事件 ID {@link String}
     * @param consumerName 消费者名称 {@link String}
     */
    public void markDone(String eventId, String consumerName) {
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_DONE, null);
    }

    /**
     * 标记消费失败（允许后续重放）。
     *
     * @param eventId 事件 ID {@link String}
     * @param consumerName 消费者名称 {@link String}
     * @param lastError 最近一次错误信息（可为空） {@link String}
     */
    public void markFail(String eventId, String consumerName, String lastError) {
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_FAIL, lastError);
    }

    private boolean isStale(Date updateTime) {
        if (updateTime == null) {
            return true;
        }
        return System.currentTimeMillis() - updateTime.getTime() >= PROCESSING_STALE_MILLIS;
    }
}
