package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 重建服务实现：按“最近 N 天 + 最大条数”重建作者 Outbox。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-01
 */
@Service
@RequiredArgsConstructor
public class FeedOutboxRebuildService implements IFeedOutboxRebuildService {

    private final IContentRepository contentRepository;
    private final IFeedOutboxRepository feedOutboxRepository;

    @Value("${feed.outbox.ttlDays:14}")
    private int outboxTtlDays;

    @Value("${feed.outbox.maxSize:1000}")
    private int outboxMaxSize;

    /**
     * 强制重建作者 Outbox。
     *
     * @param authorId 作者 ID。 {@link Long}
     */
    @Override
    public void forceRebuild(Long authorId) {
        if (authorId == null) {
            return;
        }

        int ttlDays = Math.max(1, outboxTtlDays);
        int maxSize = Math.max(1, outboxMaxSize);
        long cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ttlDays);

        String cursor = null;
        List<FeedInboxEntryVO> entries = new ArrayList<>();
        while (true) {
            // 逐页扫描作者内容，直到命中时间截止线或达到最大条数，避免一次性把历史全拉出来。
            ContentPostPageVO page = contentRepository.listUserPosts(authorId, cursor, 200);
            List<ContentPostEntity> posts = page.getPosts();
            if (posts == null || posts.isEmpty()) {
                break;
            }

            boolean reachCutoff = false;
            for (ContentPostEntity post : posts) {
                if (post == null || post.getPostId() == null || post.getCreateTime() == null || post.getStatus() == null) {
                    continue;
                }
                if (post.getStatus() != 2) {
                    continue;
                }
                if (post.getCreateTime() < cutoffMs) {
                    reachCutoff = true;
                    break;
                }
                entries.add(FeedInboxEntryVO.builder()
                        .postId(post.getPostId())
                        .publishTimeMs(post.getCreateTime())
                        .build());
                if (entries.size() >= maxSize) {
                    reachCutoff = true;
                    break;
                }
            }
            if (reachCutoff) {
                break;
            }

            cursor = page.getNextCursor();
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }

        feedOutboxRepository.replaceOutbox(authorId, entries);
    }
}

