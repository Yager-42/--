package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCoreFansRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
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
 * <p>Phase 2 规则：作者自己无条件写入；粉丝仅对 inbox key 存在的用户写入（在线推）。</p>
 *
 * @author codex
 * @since 2026-01-12
 */
@Service
@RequiredArgsConstructor
public class FeedDistributionService implements IFeedDistributionService {

    private final IRelationRepository relationRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedCoreFansRepository feedCoreFansRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;

    /**
     * fanout 粉丝批量大小，默认 200。
     */
    @Value("${feed.fanout.batchSize:200}")
    private int batchSize;

    /**
     * 大 V 判定阈值：粉丝数 >= 阈值则默认不做“全量写扩散”，改为读侧拉 Outbox（默认 500000）。
     *
     * <p>阈值 <= 0 表示禁用大 V 逻辑（始终走普通 fanout）。</p>
     */
    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    /**
     * 大 V 发布时最多推送的铁粉数量（默认 2000）。
     */
    @Value("${feed.bigv.coreFanMaxPush:2000}")
    private int coreFanMaxPush;

    /**
     * 执行 fanout：将发布内容写入在线用户的 InboxTimeline。
     *
     * <p>在线定义：{@link IFeedTimelineRepository#inboxExists(Long)} 为 true。</p>
     *
     * @param event 内容发布事件 {@link PostPublishedEvent}
     */
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

        feedOutboxRepository.addToOutbox(authorId, postId, publishTimeMs);
        feedTimelineRepository.addToInbox(authorId, postId, publishTimeMs);

        int followerCount = relationRepository.countFollowerIds(authorId);
        if (followerCount > 0 && bigvFollowerThreshold > 0 && followerCount >= bigvFollowerThreshold) {
            feedBigVPoolRepository.addToPool(authorId, postId, publishTimeMs);
            pushToCoreFans(authorId, postId, publishTimeMs);
            return;
        }

        int offset = 0;
        int limit = Math.max(1, batchSize);
        while (true) {
            List<Long> followerIds = relationRepository.listFollowerIds(authorId, offset, limit);
            if (followerIds == null || followerIds.isEmpty()) {
                break;
            }
            fanoutFollowerIds(authorId, postId, publishTimeMs, followerIds);
            offset += followerIds.size();
            if (followerIds.size() < limit) {
                break;
            }
        }
    }

    /**
     * 执行 fanout 的一个切片：只处理 authorId 粉丝列表的某一段（offset+limit）。
     *
     * <p>该方法不会写入作者自身 inbox（由上游 dispatcher 保底写入），只处理粉丝这一片。</p>
     */
    @Override
    public void fanoutSlice(Long postId, Long authorId, Long publishTimeMs, Integer offset, Integer limit) {
        if (postId == null || authorId == null || publishTimeMs == null) {
            return;
        }
        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        int safeLimit = limit == null ? Math.max(1, batchSize) : Math.max(1, limit);
        List<Long> followerIds = relationRepository.listFollowerIds(authorId, safeOffset, safeLimit);
        fanoutFollowerIds(authorId, postId, publishTimeMs, followerIds);
    }

    private void fanoutFollowerIds(Long authorId, Long postId, Long publishTimeMs, List<Long> followerIds) {
        if (followerIds == null || followerIds.isEmpty()) {
            return;
        }
        for (Long followerId : followerIds) {
            if (followerId == null || followerId.equals(authorId)) {
                continue;
            }
            if (!feedTimelineRepository.inboxExists(followerId)) {
                continue;
            }
            feedTimelineRepository.addToInbox(followerId, postId, publishTimeMs);
        }
    }

    private void pushToCoreFans(Long authorId, Long postId, Long publishTimeMs) {
        int limit = Math.max(0, coreFanMaxPush);
        if (limit == 0) {
            return;
        }
        List<Long> coreFans = feedCoreFansRepository.listCoreFans(authorId, limit);
        if (coreFans == null || coreFans.isEmpty()) {
            return;
        }
        for (Long followerId : coreFans) {
            if (followerId == null || followerId.equals(authorId)) {
                continue;
            }
            if (!feedTimelineRepository.inboxExists(followerId)) {
                continue;
            }
            feedTimelineRepository.addToInbox(followerId, postId, publishTimeMs);
        }
    }
}
