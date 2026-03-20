package cn.nexus.trigger.job.user;

import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户域 Outbox 重试与清理任务。
 *
 * <p>它不生产业务事件，只负责把已经落在表里的事件继续往前推，或者把长时间完成的历史记录清走，
 * 避免 Outbox 表无限增长。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventOutboxRetryJob {

    private final IUserEventOutboxPort outboxPort;

    /**
     * 每分钟重试待发送事件。
     */
    @Scheduled(fixedDelay = 60000)
    public void retryPending() {
        try {
            outboxPort.tryPublishPending();
        } catch (Exception e) {
            log.warn("user outbox retry failed", e);
        }
    }

    /**
     * 每日清理已完成且超过 7 天的记录。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDone() {
        // 先算出时间截止点，再统一交给 Outbox 端口清理，避免清理规则散在多处。
        long sevenDays = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int deleted = outboxPort.cleanDoneBefore(new Date(sevenDays));
        log.info("event=user.outbox.clean_done rows={}", deleted);
    }
}
