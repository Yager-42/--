package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Feed Inbox 激活服务实现：inbox miss 时，从 AuthorTimeline 合并最近索引写入 InboxTimeline。
 *
 * @author codex
 * @since 2026-01-13
 */
@Service
@RequiredArgsConstructor
public class FeedInboxActivationService implements IFeedInboxActivationService {

    private static final Comparator<FeedInboxEntryVO> ENTRY_ORDER = Comparator
            .comparing(FeedInboxEntryVO::getPublishTimeMs, Comparator.reverseOrder())
            .thenComparing(FeedInboxEntryVO::getPostId, Comparator.reverseOrder());

    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private final IFeedTimelineRepository feedTimelineRepository;

    /**
     * 每个关注对象从 AuthorTimeline 拉取的最近索引条数（默认 20）。 {@code int}
     */
    @Value("${feed.activation.perFollowingLimit:20}")
    private int perFollowingLimit;

    /**
     * 激活后写入 inbox 的条数上限（默认 200）。 {@code int}
     */
    @Value("${feed.activation.inboxSize:200}")
    private int inboxSize;

    /**
     * 激活时最多扫描的关注对象数量（默认 2000）。 {@code int}
     */
    @Value("${feed.activation.maxFollowings:2000}")
    private int maxFollowings;

    @Override
    public boolean activateIfNeeded(Long userId) {
        if (userId == null) {
            return false;
        }
        if (feedTimelineRepository.inboxExists(userId)) {
            return false;
        }

        List<FeedInboxEntryVO> entries = collectEntries(buildActivationTargets(userId));
        if (entries.isEmpty()) {
            return true;
        }
        int limit = Math.max(1, inboxSize);
        entries.sort(ENTRY_ORDER);
        for (FeedInboxEntryVO entry : entries.subList(0, Math.min(entries.size(), limit))) {
            feedTimelineRepository.addToInbox(userId, entry.getPostId(), entry.getPublishTimeMs());
        }
        return true;
    }

    private List<Long> buildActivationTargets(Long userId) {
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        targets.add(userId);

        int limit = Math.max(0, maxFollowings);
        List<Long> followings = relationAdjacencyCachePort.listFollowing(userId, limit);
        if (followings == null || followings.isEmpty()) {
            return new ArrayList<>(targets);
        }
        for (Long followingId : followings) {
            if (followingId != null) {
                targets.add(followingId);
            }
        }
        return new ArrayList<>(targets);
    }

    private List<FeedInboxEntryVO> collectEntries(List<Long> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, perFollowingLimit);
        Map<Long, FeedInboxEntryVO> byPostId = targets.stream()
                .flatMap(targetId -> safePageTimeline(targetId, limit).stream())
                .filter(entry -> entry != null && entry.getPostId() != null && entry.getPublishTimeMs() != null)
                .collect(Collectors.toMap(
                        FeedInboxEntryVO::getPostId,
                        Function.identity(),
                        this::newerEntry
                ));
        if (byPostId.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(byPostId.values());
    }

    private List<FeedInboxEntryVO> safePageTimeline(Long targetId, int limit) {
        if (targetId == null) {
            return List.of();
        }
        List<FeedInboxEntryVO> entries = feedAuthorTimelineRepository.pageTimeline(targetId, null, null, limit);
        return entries == null ? List.of() : entries;
    }

    private FeedInboxEntryVO newerEntry(FeedInboxEntryVO left, FeedInboxEntryVO right) {
        return ENTRY_ORDER.compare(left, right) <= 0 ? left : right;
    }
}
