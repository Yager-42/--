package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 评论热榜重建服务：从 MySQL 扫描一级评论，清空并重建 Redis ZSET。
 *
 * @author rr
 * @author codex
 * @since 2026-01-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentHotRankRebuildService {

    private static final int DEFAULT_SCAN_LIMIT = 5000;
    private static final int DEFAULT_KEEP_TOP = 200;

    private static final double LIKE_WEIGHT = 10D;

    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;

    /**
     * 重建某个 post 的评论热榜。
     *
     * @param postId    帖子 ID
     * @param scanLimit 扫描最近一级评论条数上限（<=0 用默认值）
     * @param keepTop   只保留 TopK（<=0 用默认值）
     */
    public void rebuildForPost(Long postId, Integer scanLimit, Integer keepTop) {
        if (postId == null) {
            return;
        }
        int scan = normalize(scanLimit, DEFAULT_SCAN_LIMIT, 50_000);
        int keep = normalize(keepTop, DEFAULT_KEEP_TOP, 10_000);

        hotRankRepository.clear(postId);
        List<CommentBriefVO> roots = commentRepository.listRecentRootBriefs(postId, scan);
        if (roots == null || roots.isEmpty()) {
            return;
        }

        for (CommentBriefVO r : roots) {
            if (r == null || r.getCommentId() == null) {
                continue;
            }
            double score = safe(r.getLikeCount()) * LIKE_WEIGHT;
            hotRankRepository.upsert(postId, r.getCommentId(), score);
        }
        hotRankRepository.trimToTop(postId, keep);

        log.info("comment hot rank rebuilt, postId={}, scanned={}, keepTop={}", postId, roots.size(), keep);
    }

    private int normalize(Integer v, int defaultValue, int max) {
        if (v == null || v <= 0) {
            return defaultValue;
        }
        return Math.min(v, max);
    }

    private long safe(Long v) {
        return v == null ? 0L : v;
    }
}
