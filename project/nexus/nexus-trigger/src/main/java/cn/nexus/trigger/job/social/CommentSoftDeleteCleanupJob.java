package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 软删评论定时物理清理任务。
 *
 * <p>规则：软删超过 N 天（默认 7）后，物理删除 interaction_comment.status=2 的记录。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommentSoftDeleteCleanupJob {

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_LIMIT = 200;

    /**
     * 配置键固定：comment.cleanup.softDeleteRetentionDays（默认 7）。
     */
    @Value("${comment.cleanup.softDeleteRetentionDays:7}")
    private int retentionDays;

    private final ICommentRepository commentRepository;

    /**
     * 每日凌晨执行一次（定时表达式写死即可）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanSoftDeletedComments() {
        int days = retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
        long cutoffMs = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int deleted = commentRepository.deleteSoftDeletedBefore(new Date(cutoffMs), DEFAULT_LIMIT);
        log.info("event=comment.soft_delete_cleanup retentionDays={} limit={} rows={}", days, DEFAULT_LIMIT, deleted);
    }
}

