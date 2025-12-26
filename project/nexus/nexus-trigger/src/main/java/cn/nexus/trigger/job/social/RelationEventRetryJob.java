package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.infrastructure.adapter.social.port.RelationBlockEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFollowEvent;
import cn.nexus.infrastructure.adapter.social.port.RelationFriendEvent;
import cn.nexus.infrastructure.dao.social.po.RelationEventInboxPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 关系事件收件箱重放/清理任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelationEventRetryJob {

    private final IRelationEventInboxPort inboxPort;
    private final IRelationEventPort eventPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 每分钟重放失败事件。
     */
    @Scheduled(fixedDelay = 60000)
    public void retryFailed() {
        List<RelationEventInboxPO> list = inboxPort.fetchRetry(100);
        for (RelationEventInboxPO po : list) {
            try {
                replay(po);
                inboxPort.markDone(po.getFingerprint());
            } catch (Exception e) {
                log.warn("重放事件失败 fp={} type={}", po.getFingerprint(), po.getEventType(), e);
                inboxPort.markFail(po.getFingerprint());
            }
        }
    }

    /**
     * 每日清理已完成 7 天前的记录。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDone() {
        long sevenDays = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int deleted = inboxPort.cleanBefore(new Date(sevenDays));
        log.info("清理收件箱已完成记录 rows={}", deleted);
    }

    private void replay(RelationEventInboxPO po) throws Exception {
        String type = po.getEventType();
        String payload = po.getPayload();
        if ("FOLLOW".equals(type)) {
            RelationFollowEvent evt = objectMapper.readValue(payload, RelationFollowEvent.class);
            eventPort.onFollow(evt.sourceId(), evt.targetId(), evt.status());
        } else if ("FRIEND".equals(type)) {
            RelationFriendEvent evt = objectMapper.readValue(payload, RelationFriendEvent.class);
            eventPort.onFriendEstablished(evt.sourceId(), evt.targetId());
        } else if ("BLOCK".equals(type)) {
            RelationBlockEvent evt = objectMapper.readValue(payload, RelationBlockEvent.class);
            eventPort.onBlock(evt.sourceId(), evt.targetId());
        } else {
            log.warn("未知事件类型，跳过 type={} fp={}", type, po.getFingerprint());
        }
    }
}
