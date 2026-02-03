package cn.nexus.trigger.job.user;

import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户域 Outbox 重试/清理任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventOutboxRetryJob {

    private final IUserEventOutboxPort outboxPort;

    /**
     * 每分钟重试待发送事件（NEW/FAIL）。
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
     * 每日清理已完成 7 天前的记录。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDone() {
        long sevenDays = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int deleted = outboxPort.cleanDoneBefore(new Date(sevenDays));
        log.info("event=user.outbox.clean_done rows={}", deleted);
    }
}

