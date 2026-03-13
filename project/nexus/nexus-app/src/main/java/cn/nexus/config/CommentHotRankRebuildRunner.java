package cn.nexus.config;

import cn.nexus.domain.social.service.CommentHotRankRebuildService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评论热榜重建启动器：仅在显式配置 postId 时执行一次。
 *
 * <p>用法（示例）：</p>
 *
 * <pre>
 *   java -jar nexus-app.jar --comment.hot.rebuild.postId=123
 * </pre>
 *
 * <p>默认不执行，避免线上误伤。</p>
 *
 * @author codex
 * @since 2026-01-22
 */
@Component
public class CommentHotRankRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommentHotRankRebuildRunner.class);

    @Value("${comment.hot.rebuild.postId:0}")
    private long postId;

    @Value("${comment.hot.rebuild.scanLimit:5000}")
    private int scanLimit;

    @Value("${comment.hot.rebuild.keepTop:200}")
    private int keepTop;

    private final CommentHotRankRebuildService rebuildService;

    public CommentHotRankRebuildRunner(CommentHotRankRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (postId <= 0) {
            return;
        }
        log.warn("comment hot rank rebuild runner enabled, postId={}, scanLimit={}, keepTop={}", postId, scanLimit, keepTop);
        rebuildService.rebuildForPost(postId, scanLimit, keepTop);
    }
}
