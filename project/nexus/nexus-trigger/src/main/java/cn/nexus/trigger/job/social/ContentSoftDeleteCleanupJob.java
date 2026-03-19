package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 软删内容定时物理清理任务。
 *
 * <p>规则：软删超过 N 天（默认 7）后，物理删除 content_post，并同步清理 content_post_type；content_history 保留审计。</p>
 *
 * @author {$authorName}
 * @since 2026-03-01
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentSoftDeleteCleanupJob {

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_LIMIT = 200;

    /**
     * 配置键固定：content.cleanup.softDeleteRetentionDays（默认 7）。
     */
    @Value("${content.cleanup.softDeleteRetentionDays:7}")
    private int retentionDays;

    private final IContentRepository contentRepository;

    /**
     * 每日凌晨执行一次（定时表达式写死即可）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanSoftDeletedPosts() {
        int days = retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
        long cutoffMs = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int deleted = contentRepository.deleteSoftDeletedBefore(new Date(cutoffMs), DEFAULT_LIMIT);
        log.info("event=content.post.soft_delete_cleanup retentionDays={} limit={} rows={}", days, DEFAULT_LIMIT, deleted);
    }
}
