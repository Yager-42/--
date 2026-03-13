package cn.nexus.config;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import cn.nexus.infrastructure.dao.social.IContentPostTypeDao;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.ContentPostTypePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐冷启动回灌启动器：分页扫描已发布内容并 upsertItem。
 *
 * <p>默认不执行，避免线上误伤；仅在显式开启 {@code feed.recommend.backfill.enabled=true} 时运行一次。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Component
public class FeedRecommendItemBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FeedRecommendItemBackfillRunner.class);

    @Value("${feed.recommend.backfill.enabled:false}")
    private boolean enabled;

    @Value("${feed.recommend.backfill.pageSize:500}")
    private int pageSize;

    private final IContentPostDao contentPostDao;
    private final IContentPostTypeDao contentPostTypeDao;
    private final IRecommendationPort recommendationPort;

    public FeedRecommendItemBackfillRunner(IContentPostDao contentPostDao,
                                          IContentPostTypeDao contentPostTypeDao,
                                          IRecommendationPort recommendationPort) {
        this.contentPostDao = contentPostDao;
        this.contentPostTypeDao = contentPostTypeDao;
        this.recommendationPort = recommendationPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int limit = Math.max(1, pageSize);
        log.warn("feed recommend item backfill runner enabled, pageSize={}", limit);

        Date cursorTime = null;
        Long cursorPostId = null;
        long total = 0L;

        while (true) {
            List<ContentPostPO> page = contentPostDao.selectPublishedPage(cursorTime, cursorPostId, limit);
            if (page == null || page.isEmpty()) {
                break;
            }

            List<Long> postIds = new ArrayList<>(page.size());
            ContentPostPO last = null;
            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                postIds.add(po.getPostId());
                last = po;
            }
            if (last == null) {
                break;
            }

            Map<Long, List<String>> typesByPostId = loadPostTypes(postIds);
            for (ContentPostPO po : page) {
                if (po == null || po.getPostId() == null) {
                    continue;
                }
                Long postId = po.getPostId();
                List<String> labels = normalizeLabels(typesByPostId.get(postId));
                long tsMs = po.getCreateTime() == null ? System.currentTimeMillis() : po.getCreateTime().getTime();
                try {
                    recommendationPort.upsertItem(postId, labels, tsMs);
                } catch (Exception e) {
                    // best-effort：失败不阻断回灌，可后续重跑。
                    log.warn("recommend backfill upsertItem failed, postId={}, labelsSize={}", postId, labels.size(), e);
                }
                total++;
            }

            cursorTime = last.getCreateTime();
            cursorPostId = last.getPostId();
            log.warn("feed recommend item backfill progress, total={}, cursorPostId={}, cursorTime={}",
                    total, cursorPostId, cursorTime == null ? null : cursorTime.getTime());

            if (page.size() < limit) {
                break;
            }
        }

        log.warn("feed recommend item backfill runner finished, total={}", total);
    }

    private Map<Long, List<String>> loadPostTypes(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<ContentPostTypePO> rows = contentPostTypeDao.selectByPostIds(postIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> map = new HashMap<>();
        for (ContentPostTypePO row : rows) {
            if (row == null || row.getPostId() == null || row.getType() == null) {
                continue;
            }
            map.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(row.getType());
        }
        return map;
    }

    private List<String> normalizeLabels(List<String> rawTypes) {
        if (rawTypes == null || rawTypes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(rawTypes.size());
        for (String raw : rawTypes) {
            if (raw == null) {
                continue;
            }
            String label = raw.trim();
            if (label.isEmpty()) {
                continue;
            }
            if (result.contains(label)) {
                continue;
            }
            result.add(label);
        }
        return result;
    }
}

