package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分发与 Feed 服务实现：提供 timeline/profile 与负反馈能力。
 *
 * @author codex
 * @since 2026-01-12
 */
@Service
@RequiredArgsConstructor
public class FeedService implements IFeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int CANDIDATE_FACTOR = 3;

    private final IContentRepository contentRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IRelationRepository relationRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;
    private final IFeedNegativeFeedbackRepository feedNegativeFeedbackRepository;
    private final IFeedInboxRebuildService feedInboxRebuildService;

    /**
     * 大 V 判定阈值：粉丝数 >= 阈值则视为大 V（默认 500000）。 {@code int}
     */
    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    /**
     * 读侧最多扫描的关注对象数量（默认 2000，与 rebuild 一致）。 {@code int}
     */
    @Value("${feed.rebuild.maxFollowings:2000}")
    private int maxFollowings;

    /**
     * 读侧最多合并多少个大 V 的 Outbox（默认 200）。 {@code int}
     */
    @Value("${feed.bigv.pull.maxBigvFollowings:200}")
    private int maxBigvFollowings;

    /**
     * 每个大 V 的 Outbox 单次最多拉多少条索引（默认 50）。 {@code int}
     */
    @Value("${feed.bigv.pull.perBigvLimit:50}")
    private int perBigvLimit;

    /**
     * 是否启用大 V 聚合池（默认 false）。 {@code boolean}
     */
    @Value("${feed.bigv.pool.enabled:false}")
    private boolean bigvPoolEnabled;

    /**
     * 大 V 聚合池分桶数量（默认 4）。 {@code int}
     */
    @Value("${feed.bigv.pool.buckets:4}")
    private int bigvPoolBuckets;

    /**
     * 聚合池拉取放大系数（默认 30）。 {@code int}
     */
    @Value("${feed.bigv.pool.fetchFactor:30}")
    private int bigvPoolFetchFactor;

    /**
     * 触发“聚合池读取”的关注数量阈值（默认 200）。 {@code int}
     */
    @Value("${feed.bigv.pool.triggerFollowings:200}")
    private int bigvPoolTriggerFollowings;

    /**
     * 获取关注页时间线（FOLLOW）：Redis InboxTimeline + 负反馈过滤 + MySQL 回表。
     *
     * <p>Phase 2：仅在首页（cursor 为空）且 inbox key miss 时触发离线重建。</p>
     *
     * @param userId   用户 ID {@link Long}
     * @param cursor   游标（上一页最后一个 postId），为空表示从最新开始 {@link String}
     * @param limit    单页数量（默认 20，最大 100） {@link Integer}
     * @param feedType feed 类型（当前仅支持 FOLLOW，占位兼容） {@link String}
     * @return 时间线结果 {@link FeedTimelineVO}
     */
    @Override
    public FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType) {
        if (userId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        String source = (feedType == null || feedType.isBlank()) ? "FOLLOW" : feedType;

        boolean homePage = cursor == null || cursor.isBlank();
        if (homePage) {
            feedInboxRebuildService.rebuildIfNeeded(userId);
        }

        MaxIdCursor maxIdCursor = homePage ? new MaxIdCursor(null, null) : resolveMaxIdCursor(cursor);
        if (!homePage && maxIdCursor == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }

        int scanLimit = normalizedLimit * CANDIDATE_FACTOR;
        List<FeedInboxEntryVO> inboxCandidates = feedTimelineRepository.pageInboxEntries(
                userId, maxIdCursor.cursorTimeMs(), maxIdCursor.cursorPostId(), scanLimit
        );
        List<Long> followings = listFollowings(userId);
        List<FeedInboxEntryVO> bigvCandidates = listBigVCandidates(userId, followings, maxIdCursor, scanLimit, normalizedLimit);
        List<FeedInboxEntryVO> candidates = mergeAndDedup(inboxCandidates, bigvCandidates, scanLimit);

        String nextCursor = candidates.isEmpty() || candidates.get(candidates.size() - 1).getPostId() == null
                ? null
                : candidates.get(candidates.size() - 1).getPostId().toString();

        List<FeedItemVO> items = buildTimelineItems(userId, source, candidates, normalizedLimit);
        return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
    }

    private MaxIdCursor resolveMaxIdCursor(String cursor) {
        Long postId = parseLong(cursor);
        if (postId == null) {
            return null;
        }
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null || post.getCreateTime() == null) {
            return null;
        }
        return new MaxIdCursor(post.getCreateTime(), postId);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<Long> listFollowings(Long userId) {
        int limit = Math.max(0, maxFollowings);
        if (limit == 0) {
            return List.of();
        }
        List<Long> list = relationAdjacencyCachePort.listFollowing(userId, limit);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Long> filtered = new ArrayList<>(list.size());
        for (Long id : list) {
            if (id == null || id.equals(userId)) {
                continue;
            }
            filtered.add(id);
        }
        return filtered;
    }

    private List<FeedInboxEntryVO> listBigVCandidates(Long userId, List<Long> followings, MaxIdCursor cursor, int scanLimit, int normalizedLimit) {
        if (followings == null || followings.isEmpty()) {
            return List.of();
        }
        if (bigvFollowerThreshold <= 0) {
            return List.of();
        }
        if (shouldUseBigVPool(followings)) {
            return listPoolCandidates(userId, followings, cursor, normalizedLimit);
        }
        List<Long> bigvAuthors = pickBigVAuthors(followings);
        if (bigvAuthors.isEmpty()) {
            return List.of();
        }
        int perLimit = Math.max(1, perBigvLimit);
        List<FeedInboxEntryVO> outboxEntries = new ArrayList<>(Math.min(scanLimit, bigvAuthors.size() * perLimit));
        for (Long authorId : bigvAuthors) {
            if (authorId == null) {
                continue;
            }
            outboxEntries.addAll(feedOutboxRepository.pageOutbox(authorId, cursor.cursorTimeMs(), cursor.cursorPostId(), perLimit));
        }
        return outboxEntries;
    }

    private boolean shouldUseBigVPool(List<Long> followings) {
        if (!bigvPoolEnabled) {
            return false;
        }
        int trigger = Math.max(0, bigvPoolTriggerFollowings);
        return followings != null && followings.size() > trigger;
    }

    private List<FeedInboxEntryVO> listPoolCandidates(Long userId, List<Long> followings, MaxIdCursor cursor, int normalizedLimit) {
        int buckets = Math.max(1, bigvPoolBuckets);
        int fetchFactor = Math.max(1, bigvPoolFetchFactor);
        int need = Math.max(1, normalizedLimit) * fetchFactor;
        int perBucket = Math.max(1, need / buckets);

        List<FeedInboxEntryVO> raw = new ArrayList<>(need);
        for (int i = 0; i < buckets; i++) {
            raw.addAll(feedBigVPoolRepository.pagePool(i, cursor.cursorTimeMs(), cursor.cursorPostId(), perBucket));
        }
        if (raw.isEmpty()) {
            return List.of();
        }

        raw.sort(entryComparator());
        if (raw.size() > need) {
            raw = new ArrayList<>(raw.subList(0, need));
        }

        Set<Long> followingSet = new HashSet<>(followings);
        List<Long> ids = new ArrayList<>(raw.size());
        for (FeedInboxEntryVO entry : raw) {
            if (entry == null || entry.getPostId() == null) {
                continue;
            }
            ids.add(entry.getPostId());
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        List<ContentPostEntity> posts = contentRepository.listPostsByIds(ids);
        if (posts.isEmpty()) {
            cleanupMissingIndexes(userId, ids, List.of());
            return List.of();
        }

        cleanupMissingIndexes(userId, ids, posts);

        List<FeedInboxEntryVO> filtered = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null || post.getCreateTime() == null || post.getUserId() == null) {
                continue;
            }
            if (!followingSet.contains(post.getUserId())) {
                continue;
            }
            filtered.add(FeedInboxEntryVO.builder().postId(post.getPostId()).publishTimeMs(post.getCreateTime()).build());
        }
        return filtered;
    }

    private List<Long> pickBigVAuthors(List<Long> followings) {
        int limit = Math.max(0, maxBigvFollowings);
        if (limit == 0) {
            return List.of();
        }
        List<Long> bigv = new ArrayList<>(Math.min(followings.size(), limit));
        for (Long authorId : followings) {
            if (authorId == null) {
                continue;
            }
            int followerCount = relationRepository.countFollowerIds(authorId);
            if (followerCount >= bigvFollowerThreshold) {
                bigv.add(authorId);
            }
            if (bigv.size() >= limit) {
                break;
            }
        }
        return bigv;
    }

    private List<FeedInboxEntryVO> mergeAndDedup(List<FeedInboxEntryVO> inbox, List<FeedInboxEntryVO> extra, int limit) {
        int normalizedLimit = Math.max(1, limit);
        List<FeedInboxEntryVO> merged = new ArrayList<>();
        if (inbox != null && !inbox.isEmpty()) {
            merged.addAll(inbox);
        }
        if (extra != null && !extra.isEmpty()) {
            merged.addAll(extra);
        }
        if (merged.isEmpty()) {
            return List.of();
        }

        merged.removeIf(e -> e == null || e.getPostId() == null || e.getPublishTimeMs() == null);
        if (merged.isEmpty()) {
            return List.of();
        }

        merged.sort(entryComparator());
        Set<Long> seen = new HashSet<>();
        List<FeedInboxEntryVO> dedup = new ArrayList<>(Math.min(merged.size(), normalizedLimit));
        for (FeedInboxEntryVO entry : merged) {
            if (seen.add(entry.getPostId())) {
                dedup.add(entry);
            }
            if (dedup.size() >= normalizedLimit) {
                break;
            }
        }
        return dedup;
    }

    private Comparator<FeedInboxEntryVO> entryComparator() {
        return (a, b) -> {
            if (a == b) {
                return 0;
            }
            if (a == null) {
                return 1;
            }
            if (b == null) {
                return -1;
            }
            Long ta = a.getPublishTimeMs();
            Long tb = b.getPublishTimeMs();
            int timeCompare = compareDesc(ta, tb);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return compareDesc(a.getPostId(), b.getPostId());
        };
    }

    private int compareDesc(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Long.compare(b, a);
    }

    private List<FeedItemVO> buildTimelineItems(Long userId, String source, List<FeedInboxEntryVO> candidates, int normalizedLimit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = new ArrayList<>(candidates.size());
        for (FeedInboxEntryVO entry : candidates) {
            if (entry == null || entry.getPostId() == null) {
                continue;
            }
            candidateIds.add(entry.getPostId());
        }
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        List<ContentPostEntity> posts = contentRepository.listPostsByIds(candidateIds);
        if (posts.isEmpty()) {
            cleanupMissingIndexes(userId, candidateIds, List.of());
            return List.of();
        }

        cleanupMissingIndexes(userId, candidateIds, posts);

        Set<Integer> negativeTypes = feedNegativeFeedbackRepository.listContentTypes(userId);
        List<FeedItemVO> items = new ArrayList<>(Math.min(posts.size(), normalizedLimit));
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            if (post.getPostId() != null && feedNegativeFeedbackRepository.contains(userId, post.getPostId())) {
                continue;
            }
            if (post.getMediaType() != null && negativeTypes.contains(post.getMediaType())) {
                continue;
            }
            items.add(FeedItemVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .text(post.getContentText())
                    .publishTime(post.getCreateTime())
                    .source(source)
                    .build());
            if (items.size() >= normalizedLimit) {
                break;
            }
        }
        return items;
    }

    private void cleanupMissingIndexes(Long userId, List<Long> candidateIds, List<ContentPostEntity> foundPosts) {
        if (userId == null || candidateIds == null || candidateIds.isEmpty()) {
            return;
        }
        Set<Long> foundIds = new HashSet<>();
        if (foundPosts != null) {
            for (ContentPostEntity post : foundPosts) {
                if (post != null && post.getPostId() != null) {
                    foundIds.add(post.getPostId());
                }
            }
        }

        for (Long postId : candidateIds) {
            if (postId == null || foundIds.contains(postId)) {
                continue;
            }
            feedTimelineRepository.removeFromInbox(userId, postId);
            ContentPostEntity raw = contentRepository.findPost(postId);
            if (raw == null || raw.getUserId() == null) {
                continue;
            }
            feedOutboxRepository.removeFromOutbox(raw.getUserId(), postId);
            feedBigVPoolRepository.removeFromPool(raw.getUserId(), postId);
        }
    }

    /**
     * 获取个人页时间线（PROFILE）：直接从内容仓储分页读取。
     *
     * @param targetId  目标用户 ID {@link Long}
     * @param visitorId 访问者用户 ID {@link Long}
     * @param cursor    游标（\"{lastCreateTimeMs}:{lastPostId}\"），为空表示从最新开始 {@link String}
     * @param limit     单页数量（默认 20，最大 100） {@link Integer}
     * @return 时间线结果 {@link FeedTimelineVO}
     */
    @Override
    public FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit) {
        if (targetId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        ContentPostPageVO page = contentRepository.listUserPosts(targetId, cursor, normalizedLimit);
        List<ContentPostEntity> posts = page.getPosts() == null ? List.of() : page.getPosts();
        if (posts.isEmpty()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(page.getNextCursor()).build();
        }
        List<FeedItemVO> items = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            items.add(FeedItemVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .text(post.getContentText())
                    .publishTime(post.getCreateTime())
                    .source("PROFILE")
                    .build());
        }
        return FeedTimelineVO.builder().items(items).nextCursor(page.getNextCursor()).build();
    }

    /**
     * 提交负反馈：当前实现仅记录 targetId，用于读侧过滤。
     *
     * @param userId     用户 ID {@link Long}
     * @param targetId   目标 ID（通常为 postId） {@link Long}
     * @param type       负反馈类型（占位） {@link String}
     * @param reasonCode 原因码（占位） {@link String}
     * @param extraTags  额外标签（占位） {@link List} {@link String}
     * @return 操作结果 {@link OperationResultVO}
     */
    @Override
    public OperationResultVO negativeFeedback(Long userId, Long targetId, String type, String reasonCode, List<String> extraTags) {
        if (userId == null || targetId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(targetId)
                    .status("INVALID")
                    .message("参数错误")
                    .build();
        }
        feedNegativeFeedbackRepository.add(userId, targetId, type, reasonCode);
        ContentPostEntity post = contentRepository.findPost(targetId);
        if (post != null && post.getMediaType() != null) {
            feedNegativeFeedbackRepository.addContentType(userId, post.getMediaType());
        }
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("RECORDED")
                .message(reasonCode)
                .build();
    }

    /**
     * 撤销负反馈。
     *
     * @param userId   用户 ID {@link Long}
     * @param targetId 目标 ID {@link Long}
     * @return 操作结果 {@link OperationResultVO}
     */
    @Override
    public OperationResultVO cancelNegativeFeedback(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(targetId)
                    .status("INVALID")
                    .message("参数错误")
                    .build();
        }
        feedNegativeFeedbackRepository.remove(userId, targetId);
        ContentPostEntity post = contentRepository.findPost(targetId);
        if (post != null && post.getMediaType() != null) {
            feedNegativeFeedbackRepository.removeContentType(userId, post.getMediaType());
        }
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("CANCELLED")
                .message("已撤销负反馈")
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record MaxIdCursor(Long cursorTimeMs, Long cursorPostId) {
    }
}
