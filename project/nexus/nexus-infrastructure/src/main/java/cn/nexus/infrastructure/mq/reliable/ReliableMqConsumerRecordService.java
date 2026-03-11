package cn.nexus.infrastructure.mq.reliable;

import cn.nexus.infrastructure.dao.social.IReliableMqConsumerRecordDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqConsumerRecordPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通用消费幂等记录：
 * 1. 同一 eventId + consumerName 只允许一个成功副作用。
 * 2. 失败后允许 replay 重新进入。
 */
@Component
@RequiredArgsConstructor
public class ReliableMqConsumerRecordService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAIL = "FAIL";

    private final IReliableMqConsumerRecordDao consumerRecordDao;

    public boolean start(String eventId, String consumerName, String payloadJson) {
        if (eventId == null || eventId.isBlank() || consumerName == null || consumerName.isBlank()) {
            return false;
        }
        ReliableMqConsumerRecordPO po = new ReliableMqConsumerRecordPO();
        po.setEventId(eventId);
        po.setConsumerName(consumerName);
        po.setPayloadJson(payloadJson);
        po.setStatus(STATUS_PROCESSING);
        if (consumerRecordDao.insertIgnore(po) > 0) {
            return true;
        }
        ReliableMqConsumerRecordPO existed = consumerRecordDao.selectOne(eventId, consumerName);
        if (existed == null) {
            return false;
        }
        if (STATUS_DONE.equals(existed.getStatus()) || STATUS_PROCESSING.equals(existed.getStatus())) {
            return false;
        }
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_PROCESSING, null);
        return true;
    }

    public void markDone(String eventId, String consumerName) {
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_DONE, null);
    }

    public void markFail(String eventId, String consumerName, String lastError) {
        consumerRecordDao.updateStatus(eventId, consumerName, STATUS_FAIL, lastError);
    }
}
