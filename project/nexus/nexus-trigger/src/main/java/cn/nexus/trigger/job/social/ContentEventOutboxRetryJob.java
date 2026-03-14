package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IContentEventOutboxPort;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 内容域 Outbox 重试/清理任务。
 *
 * @author {$authorName}
 * @since 2026-02-03
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentEventOutboxRetryJob {

    private final IContentEventOutboxPort outboxPort;

    /**
     * 每分钟重试待发送事件（NEW/FAIL）。
     */
    @Scheduled(fixedDelay = 60000)
    public void retryPending() {
        try {
            outboxPort.tryPublishPending();
        } catch (Exception e) {
            log.warn("content outbox retry failed", e);
        }
    }

    /**
     * 每日清理已发送 7 天前的记录。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanDone() {
        long sevenDays = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int deleted = outboxPort.cleanDoneBefore(new Date(sevenDays));
        log.info("event=content.outbox.clean_done rows={}", deleted);
    }
}
