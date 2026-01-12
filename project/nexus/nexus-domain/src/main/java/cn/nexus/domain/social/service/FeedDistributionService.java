package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.types.event.PostPublishedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Feed 分发服务实现：处理内容发布后的写扩散（fanout）。
 *
 * <p>Phase 1 规则：对发布者与其全部粉丝写 InboxTimeline（分页拉粉丝）。</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Service
@RequiredArgsConstructor
public class FeedDistributionService implements IFeedDistributionService {

    private final IRelationRepository relationRepository;
    private final IFeedTimelineRepository feedTimelineRepository;

    /**
     * fanout 粉丝批量大小，默认 200。
     */
    @Value("${feed.fanout.batchSize:200}")
    private int batchSize;

    @Override
    public void fanout(PostPublishedEvent event) {
        if (event == null) {
            return;
        }
        Long postId = event.getPostId();
        Long authorId = event.getAuthorId();
        Long publishTimeMs = event.getPublishTimeMs();
        if (postId == null || authorId == null || publishTimeMs == null) {
            return;
        }

        feedTimelineRepository.addToInbox(authorId, postId, publishTimeMs);

        int offset = 0;
        int limit = Math.max(1, batchSize);
        while (true) {
            List<Long> followerIds = relationRepository.listFollowerIds(authorId, offset, limit);
            if (followerIds == null || followerIds.isEmpty()) {
                break;
            }
            for (Long followerId : followerIds) {
                if (followerId == null || followerId.equals(authorId)) {
                    continue;
                }
                feedTimelineRepository.addToInbox(followerId, postId, publishTimeMs);
            }
            offset += followerIds.size();
            if (followerIds.size() < limit) {
                break;
            }
        }
    }
}

