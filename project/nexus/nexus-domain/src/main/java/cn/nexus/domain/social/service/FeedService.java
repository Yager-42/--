package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedRecommendSessionRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedNeighborsCursor;
import cn.nexus.domain.social.model.valobj.FeedPopularCursor;
import cn.nexus.domain.social.model.valobj.FeedRecommendCursor;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 分发与 Feed 服务实现：提供 timeline/profile 等读侧能力。
 *
 * @author rr
 * @author codex
 * @since 2026-01-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService implements IFeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int RELATION_BLOCK = 3;
    private static final int CONTENT_STATUS_PUBLISHED = 2;
    private static final int FOLLOW_SCAN_MAX_ROUNDS = 3;

    private final IContentRepository contentRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private final IFeedInboxActivationService feedInboxActivationService;
    private final IFeedGlobalLatestRepository feedGlobalLatestRepository;
    private final IFeedRecommendSessionRepository feedRecommendSessionRepository;
    private final IRecommendationPort recommendationPort;
    private final FeedCardAssembleService feedCardAssembleService;

    /**
     * 大 V 判定阈值：粉丝数 >= 阈值则视为大 V（默认 500000）。 {@code int}
     */
    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    /**
     * 读侧最多扫描的关注对象数量（默认 2000）。 {@code int}
     */
    @Value("${feed.follow.maxFollowings:2000}")
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
     * 推荐候选预取系数：appendBatch = limit * prefetchFactor（默认 5）。 {@code int}
     */
    @Value("${feed.recommend.prefetchFactor:5}")
    private int recommendPrefetchFactor;

    /**
     * 推荐扫描预算系数：scanBudget = limit * scanFactor（默认 10）。 {@code int}
     */
    @Value("${feed.recommend.scanFactor:10}")
    private int recommendScanFactor;

    /**
     * 推荐候选追加最大轮数（默认 3）。 {@code int}
     */
    @Value("${feed.recommend.maxAppendRounds:3}")
    private int recommendMaxAppendRounds;

    @Value("${feed.recommend.trendingRecommenderName:trending}")
    private String trendingRecommenderName;

    @Value("${feed.recommend.latestRecommenderName:latest}")
    private String latestRecommenderName;

    @Value("${feed.recommend.similarRecommenderName:similar}")
    private String similarRecommenderName;

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
    public FeedTimelineVO timeline(Long userId,
                                   String cursor,
                                   Integer limit,
                                   String feedType,
                                   String direction,
                                   Long cursorTs,
                                   Long cursorPostId) {
        if (userId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        String source = (feedType == null || feedType.isBlank()) ? "FOLLOW" : feedType;
        String normalizedFeedType = source.trim();

        // Phase 3：推荐流必须走独立链路，不能复用 FOLLOW 的 inbox/outbox 读取。
        if ("RECOMMEND".equalsIgnoreCase(normalizedFeedType)) {
            return recommendTimeline(userId, cursor, normalizedLimit);
        }
        if ("POPULAR".equalsIgnoreCase(normalizedFeedType)) {
            return popularTimeline(userId, cursor, normalizedLimit);
        }
        if ("NEIGHBORS".equalsIgnoreCase(normalizedFeedType)) {
            return neighborsTimeline(userId, cursor, normalizedLimit);
        }
        return followTimeline(userId, normalizedLimit, direction, cursorTs, cursorPostId);
    }

    /**
     * 推荐页时间线（RECOMMEND）：session cache + scanIndex 扫描推进 + 过滤/回表/组装。
     *
     * <p>注意：scanIndex 是扫描指针，不是“已返回条数”。nextCursor 必须推进到扫描结束位置。</p>
     */
    private FeedTimelineVO recommendTimeline(Long userId, String cursor, int normalizedLimit) {
        FeedRecommendCursor.Parsed parsed = FeedRecommendCursor.parse(cursor);
        String sessionId = parsed == null ? null : parsed.sessionId();
        long scanIndex = parsed == null ? 0L : parsed.scanIndex();

        boolean validSession = sessionId != null && feedRecommendSessionRepository.sessionExists(userId, sessionId);
        if (!validSession) {
            sessionId = newRecommendSessionId();
            scanIndex = 0L;
        }

        int scanFactor = Math.max(1, recommendScanFactor);
        int prefetchFactor = Math.max(1, recommendPrefetchFactor);
        int maxRounds = Math.max(1, recommendMaxAppendRounds);
        int scanBudget = Math.max(1, normalizedLimit) * scanFactor;
        int appendBatch = Math.max(1, normalizedLimit) * prefetchFactor;

        EnsureRecommendResult ensureResult = ensureRecommendCandidates(userId, sessionId, scanIndex, scanBudget, appendBatch, maxRounds);

        List<FeedInboxEntryVO> accepted = new ArrayList<>(normalizedLimit);
        Set<Long> seenAuthors = new HashSet<>();
        long idx = Math.max(0L, scanIndex);
        int scanned = 0;

        // 小批量回表：减少 DB 次数，同时保证 scanIndex 只推进“实际扫描过的候选”。
        final int chunkSize = 50;
        while (accepted.size() < normalizedLimit && scanned < scanBudget) {
            int remaining = scanBudget - scanned;
            int take = Math.min(chunkSize, remaining);
            if (take <= 0) {
                break;
            }
            long endIndex = idx + take - 1;
            List<Long> batch = feedRecommendSessionRepository.range(userId, sessionId, idx, endIndex);
            if (batch.isEmpty()) {
                break;
            }

            List<Long> toFetch = new ArrayList<>(batch.size());
            for (Long candidateId : batch) {
                if (candidateId == null) {
                    continue;
                }
                toFetch.add(candidateId);
            }
            Map<Long, ContentPostEntity> postById = mapById(contentRepository.listPostsByIds(toFetch));

            for (Long candidateId : batch) {
                idx++;
                scanned++;
                if (scanned > scanBudget) {
                    break;
                }
                if (candidateId == null) {
                    continue;
                }
                ContentPostEntity post = postById.get(candidateId);
                if (post == null) {
                    continue;
                }
                Long authorId = post.getUserId();
                if (authorId == null || !seenAuthors.add(authorId)) {
                    continue;
                }
                accepted.add(FeedInboxEntryVO.builder()
                        .postId(post.getPostId())
                        .publishTimeMs(post.getCreateTime())
                        .build());
                if (accepted.size() >= normalizedLimit) {
                    break;
                }
            }
        }

        List<FeedItemVO> items = feedCardAssembleService.assemble(userId, "RECOMMEND", accepted, normalizedLimit);
        String nextCursor = idx == scanIndex ? null : FeedRecommendCursor.format(sessionId, idx);
        writeRecommendReadFeedbackAsync(userId, items);
        log.info("feed recommend timeline, userId={}, sessionId={}, scanIndex={}, limit={}, scanned={}, returned={}, appendRounds={}, fallbackReason={}",
                userId, sessionId, scanIndex, normalizedLimit, scanned, items.size(),
                ensureResult == null ? 0 : ensureResult.appendRounds(),
                ensureResult == null ? "" : ensureResult.fallbackReason());
        return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
    }

    /**
     * 热门页时间线（POPULAR）：以 offset 为扫描指针，按“候选 -> 过滤 -> 回表 -> 组装”输出。
     */
    private FeedTimelineVO popularTimeline(Long userId, String cursor, int normalizedLimit) {
        FeedPopularCursor.Parsed parsed = FeedPopularCursor.parse(cursor);
        long offset = parsed == null ? 0L : parsed.offset();

        int scanFactor = Math.max(1, recommendScanFactor);
        int prefetchFactor = Math.max(1, recommendPrefetchFactor);
        int maxRounds = Math.max(1, recommendMaxAppendRounds);
        int scanBudget = Math.max(1, normalizedLimit) * scanFactor;
        int appendBatch = Math.max(1, normalizedLimit) * prefetchFactor;

        List<FeedInboxEntryVO> accepted = new ArrayList<>(normalizedLimit);
        Set<Long> seenAuthors = new HashSet<>();
        long idx = Math.max(0L, offset);
        int scanned = 0;
        int rounds = 0;

        while (accepted.size() < normalizedLimit && scanned < scanBudget && rounds < maxRounds) {
            Integer safeOffset = toInt(idx);
            if (safeOffset == null) {
                break;
            }
            List<Long> candidates = recommendationPort.nonPersonalized(trendingRecommenderName, userId, appendBatch, safeOffset);
            if (candidates == null || candidates.isEmpty()) {
                break;
            }

            List<Long> toFetch = new ArrayList<>(candidates.size());
            for (Long candidateId : candidates) {
                if (candidateId == null) {
                    continue;
                }
                toFetch.add(candidateId);
            }
            Map<Long, ContentPostEntity> postById = mapById(contentRepository.listPostsByIds(toFetch));

            for (Long candidateId : candidates) {
                idx++;
                scanned++;
                if (scanned > scanBudget) {
                    break;
                }
                if (candidateId == null) {
                    continue;
                }
                ContentPostEntity post = postById.get(candidateId);
                if (post == null) {
                    continue;
                }
                Long authorId = post.getUserId();
                if (authorId == null || !seenAuthors.add(authorId)) {
                    continue;
                }
                accepted.add(FeedInboxEntryVO.builder()
                        .postId(post.getPostId())
                        .publishTimeMs(post.getCreateTime())
                        .build());
                if (accepted.size() >= normalizedLimit) {
                    break;
                }
            }
            rounds++;
        }

        List<FeedItemVO> items = feedCardAssembleService.assemble(userId, "POPULAR", accepted, normalizedLimit);
        String nextCursor = idx == offset ? null : FeedPopularCursor.format(idx);
        return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
    }

    /**
     * 相关推荐时间线（NEIGHBORS）：seedPostId 必填，offset 为扫描指针。
     */
    private FeedTimelineVO neighborsTimeline(Long userId, String cursor, int normalizedLimit) {
        FeedNeighborsCursor.Parsed parsed = FeedNeighborsCursor.parse(cursor);
        if (parsed == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        long seedPostId = parsed.seedPostId();
        long offset = parsed.offset();

        int scanFactor = Math.max(1, recommendScanFactor);
        int scanBudget = Math.max(1, normalizedLimit) * scanFactor;

        long idx = Math.max(0L, offset);
        int needN = Math.max(1, safeNeighborsN(idx, scanBudget));
        List<Long> all = recommendationPort.itemToItem(similarRecommenderName, seedPostId, needN);
        if (all == null || all.isEmpty()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        Integer start = toInt(idx);
        if (start == null || start >= all.size()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int endExclusive = Math.min(all.size(), start + scanBudget);
        List<Long> slice = all.subList(start, endExclusive);

        List<Long> toFetch = new ArrayList<>(slice.size());
        for (Long candidateId : slice) {
            if (candidateId == null) {
                continue;
            }
            toFetch.add(candidateId);
        }
        Map<Long, ContentPostEntity> postById = mapById(contentRepository.listPostsByIds(toFetch));

        List<FeedInboxEntryVO> accepted = new ArrayList<>(normalizedLimit);
        Set<Long> seenAuthors = new HashSet<>();
        int scanned = 0;
        for (Long candidateId : slice) {
            idx++;
            scanned++;
            if (scanned > scanBudget) {
                break;
            }
            if (candidateId == null) {
                continue;
            }
            ContentPostEntity post = postById.get(candidateId);
            if (post == null) {
                continue;
            }
            Long authorId = post.getUserId();
            if (authorId == null || !seenAuthors.add(authorId)) {
                continue;
            }
            accepted.add(FeedInboxEntryVO.builder()
                    .postId(post.getPostId())
                    .publishTimeMs(post.getCreateTime())
                    .build());
            if (accepted.size() >= normalizedLimit) {
                break;
            }
        }

        List<FeedItemVO> items = feedCardAssembleService.assemble(userId, "NEIGHBORS", accepted, normalizedLimit);
        String nextCursor = idx == offset ? null : FeedNeighborsCursor.format(seedPostId, idx);
        return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
    }

    private Integer toInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private int safeNeighborsN(long offset, int scanBudget) {
        long n = Math.max(1L, offset + scanBudget);
        if (n > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) n;
    }

    private Map<Long, ContentPostEntity> mapById(List<ContentPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            return Map.of();
        }
        Map<Long, ContentPostEntity> map = new HashMap<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null) {
                continue;
            }
            map.put(post.getPostId(), post);
        }
        return map;
    }

    /**
     * 确保 session 候选足够：候选不足时，优先从 gorse 拉取；失败/为空则降级用全站 latest 补齐。
     */
    private EnsureRecommendResult ensureRecommendCandidates(Long userId,
                                                           String sessionId,
                                                           long scanIndex,
                                                           int scanBudget,
                                                           int appendBatch,
                                                           int maxAppendRounds) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return new EnsureRecommendResult(0, "INVALID");
        }
        long needSize = Math.max(0L, scanIndex) + Math.max(1, scanBudget);

        int rounds = 0;
        boolean gorseFailed = false;
        String fallbackReason = "NONE";

        int maxRounds = Math.max(1, maxAppendRounds);
        int batch = Math.max(1, appendBatch);
        List<Long> followings = null;
        while (feedRecommendSessionRepository.size(userId, sessionId) < needSize && rounds < maxRounds) {
            // 1) 优先从 gorse 拉候选，写入 session（LIST+SET 去重）。
            if (!gorseFailed) {
                try {
                    List<Long> candidates = recommendationPort.recommend(userId, batch);
                    if (candidates == null || candidates.isEmpty()) {
                        if ("NONE".equals(fallbackReason)) {
                            fallbackReason = "GORSE_EMPTY";
                        }
                    } else {
                        int appended = feedRecommendSessionRepository.appendCandidates(userId, sessionId, candidates);
                        if (appended <= 0 && "NONE".equals(fallbackReason)) {
                            fallbackReason = "GORSE_EMPTY";
                        }
                    }
                } catch (Exception e) {
                    gorseFailed = true;
                    fallbackReason = "GORSE_FAILED";
                    log.warn("gorse recommend failed, userId={}, sessionId={}, scanIndex={}, batch={}",
                            userId, sessionId, scanIndex, batch, e);
                }
            }

            if (feedRecommendSessionRepository.size(userId, sessionId) >= needSize) {
                rounds++;
                break;
            }

            try {
                List<Long> trending = recommendationPort.nonPersonalized(trendingRecommenderName, userId, batch, 0);
                if (trending != null && !trending.isEmpty()) {
                    feedRecommendSessionRepository.appendCandidates(userId, sessionId, trending);
                }
            } catch (Exception e) {
                log.warn("gorse trending failed, userId={}, sessionId={}, batch={}", userId, sessionId, batch, e);
            }

            if (feedRecommendSessionRepository.size(userId, sessionId) >= needSize) {
                rounds++;
                break;
            }

            if (followings == null) {
                followings = listFollowings(userId);
            }
            List<Long> socialCandidates = listRecommendFollowCandidates(followings, batch);
            if (!socialCandidates.isEmpty()) {
                feedRecommendSessionRepository.appendCandidates(userId, sessionId, socialCandidates);
            }

            if (feedRecommendSessionRepository.size(userId, sessionId) >= needSize) {
                rounds++;
                break;
            }

            // 2) gorse 不可用或候选不足：降级用全站 latest 补齐（internal cursor：timeMs:postId）。
            String latestCursor = feedRecommendSessionRepository.getLatestCursor(userId, sessionId);
            LatestCursor parsed = LatestCursor.parse(latestCursor);
            List<FeedInboxEntryVO> entries = feedGlobalLatestRepository.pageLatest(
                    parsed == null ? null : parsed.timeMs,
                    parsed == null ? null : parsed.postId,
                    batch
            );
            if (entries == null || entries.isEmpty()) {
                rounds++;
                break;
            }

            List<Long> ids = new ArrayList<>(entries.size());
            FeedInboxEntryVO last = null;
            for (FeedInboxEntryVO entry : entries) {
                if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                    continue;
                }
                ids.add(entry.getPostId());
                last = entry;
            }

            feedRecommendSessionRepository.appendCandidates(userId, sessionId, ids);
            if (last != null) {
                feedRecommendSessionRepository.setLatestCursor(userId, sessionId, LatestCursor.format(last.getPublishTimeMs(), last.getPostId()));
            }
            rounds++;
        }
        return new EnsureRecommendResult(rounds, fallbackReason);
    }

    private String newRecommendSessionId() {
        String raw = UUID.randomUUID().toString().replace("-", "");
        return raw.length() <= 12 ? raw : raw.substring(0, 12);
    }

    private record EnsureRecommendResult(int appendRounds, String fallbackReason) {
    }

    private List<Long> listRecommendFollowCandidates(List<Long> followings, int batch) {
        if (followings == null || followings.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(batch);
        for (Long authorId : followings) {
            if (authorId == null) {
                continue;
            }
            List<FeedInboxEntryVO> entries = feedAuthorTimelineRepository.pageTimeline(authorId, null, null, 1);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            FeedInboxEntryVO first = entries.get(0);
            if (first != null && first.getPostId() != null) {
                ids.add(first.getPostId());
            }
            if (ids.size() >= batch) {
                break;
            }
        }
        return ids;
    }

    private void writeRecommendReadFeedbackAsync(Long userId, List<FeedItemVO> items) {
        if (userId == null || items == null || items.isEmpty()) {
            return;
        }
        List<Long> postIds = new ArrayList<>(items.size());
        for (FeedItemVO item : items) {
            if (item == null || item.getPostId() == null) {
                continue;
            }
            postIds.add(item.getPostId());
        }
        if (postIds.isEmpty()) {
            return;
        }
        long tsMs = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            for (Long postId : postIds) {
                if (postId == null) {
                    continue;
                }
                try {
                    recommendationPort.insertFeedback(userId, postId, "read", tsMs);
                } catch (Exception e) {
                    // best-effort：失败不影响主链路
                    log.warn("recommend read feedback failed, userId={}, postId={}", userId, postId, e);
                }
            }
        });
    }

    private record LatestCursor(Long timeMs, Long postId) {

        private static LatestCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.trim().split(":", -1);
            if (parts.length != 2) {
                return null;
            }
            try {
                long time = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                if (time < 0 || id < 0) {
                    return null;
                }
                return new LatestCursor(time, id);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static String format(Long timeMs, Long postId) {
            if (timeMs == null || postId == null) {
                return null;
            }
            return timeMs + ":" + postId;
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

    private List<FeedItemVO> buildTimelineItems(Long userId,
                                                String source,
                                                List<FeedInboxEntryVO> candidates,
                                                int normalizedLimit,
                                                List<Long> followings,
                                                boolean enforceFollowingAuthors) {
        List<FeedInboxEntryVO> filtered = filterTimelineCandidates(userId, source, candidates, followings, enforceFollowingAuthors);
        if (filtered.isEmpty()) {
            return List.of();
        }
        return feedCardAssembleService.assemble(userId, source, filtered, normalizedLimit);
    }

    private List<FeedInboxEntryVO> filterTimelineCandidates(Long userId,
                                                           String source,
                                                           List<FeedInboxEntryVO> candidates,
                                                           List<Long> followings,
                                                           boolean enforceFollowingAuthors) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = new ArrayList<>(candidates.size());
        for (FeedInboxEntryVO entry : candidates) {
            if (entry != null && entry.getPostId() != null) {
                candidateIds.add(entry.getPostId());
            }
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

        Map<Long, ContentPostEntity> postById = mapById(posts);
        boolean followSource = userId != null && source != null && "FOLLOW".equalsIgnoreCase(source.trim());
        Set<Long> allowedAuthors = null;
        if (followSource && enforceFollowingAuthors) {
            allowedAuthors = new HashSet<>();
            allowedAuthors.add(userId);
            if (followings != null) {
                for (Long id : followings) {
                    if (id != null) {
                        allowedAuthors.add(id);
                    }
                }
            }
        }

        List<FeedInboxEntryVO> filtered = new ArrayList<>(candidates.size());
        for (FeedInboxEntryVO entry : candidates) {
            if (entry == null || entry.getPostId() == null) {
                continue;
            }
            ContentPostEntity post = postById.get(entry.getPostId());
            if (post == null) {
                continue;
            }
            if (followSource && !isPublished(post)) {
                feedTimelineRepository.removeFromInbox(userId, entry.getPostId());
                continue;
            }
            Long authorId = post.getUserId();
            if (followSource) {
                if (authorId == null) {
                    continue;
                }
                if (allowedAuthors != null && !allowedAuthors.contains(authorId)) {
                    continue;
                }
                if (isBlockedBetween(authorId, userId)) {
                    continue;
                }
            }
            filtered.add(entry);
        }
        return filtered;
    }

    private boolean isBlockedBetween(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return false;
        }
        if (sourceId.equals(targetId)) {
            return false;
        }
        return relationRepository.findRelation(sourceId, targetId, RELATION_BLOCK) != null
                || relationRepository.findRelation(targetId, sourceId, RELATION_BLOCK) != null;
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
        if (visitorId != null) {
            boolean blocked1 = relationRepository.findRelation(targetId, visitorId, RELATION_BLOCK) != null;
            boolean blocked2 = relationRepository.findRelation(visitorId, targetId, RELATION_BLOCK) != null;
            if (blocked1 || blocked2) {
                return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
            }
        }
        int normalizedLimit = normalizeLimit(limit);
        ContentPostPageVO page = contentRepository.listUserPosts(targetId, cursor, normalizedLimit);
        List<ContentPostEntity> posts = page.getPosts() == null ? List.of() : page.getPosts();
        if (posts.isEmpty()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(page.getNextCursor()).build();
        }
        List<FeedInboxEntryVO> entries = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null || post.getPostId() == null || post.getCreateTime() == null) {
                continue;
            }
            entries.add(FeedInboxEntryVO.builder().postId(post.getPostId()).publishTimeMs(post.getCreateTime()).build());
        }
        List<FeedItemVO> items = feedCardAssembleService.assemble(visitorId, "PROFILE", entries, normalizedLimit);
        return FeedTimelineVO.builder().items(items).nextCursor(page.getNextCursor()).build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private FeedTimelineVO followTimeline(Long userId, int normalizedLimit, String direction, Long cursorTs, Long cursorPostId) {
        boolean refresh = direction == null || direction.isBlank() || "REFRESH".equalsIgnoreCase(direction);
        if (!refresh && (cursorTs == null || cursorPostId == null)) {
            return FeedTimelineVO.builder()
                    .items(List.of())
                    .nextCursor(null)
                    .nextCursorTs(null)
                    .nextCursorPostId(null)
                    .hasMore(false)
                    .build();
        }
        if (refresh) {
            feedInboxActivationService.activateIfNeeded(userId);
        }

        MaxIdCursor maxIdCursor = refresh ? new MaxIdCursor(null, null) : new MaxIdCursor(cursorTs, cursorPostId);
        List<Long> followings = listFollowings(userId);
        AuthorBuckets authorBuckets = splitAuthorsByCategory(followings);

        List<FeedInboxEntryVO> accepted = new ArrayList<>(normalizedLimit);
        Set<Long> acceptedPostIds = new HashSet<>();
        FeedInboxEntryVO lastScanned = null;
        boolean hasMore = false;
        int rounds = 0;
        while (accepted.size() < normalizedLimit && rounds < FOLLOW_SCAN_MAX_ROUNDS) {
            MergeResult mergeResult = pageAndMergeFollowCandidates(userId, authorBuckets, maxIdCursor, normalizedLimit);
            if (mergeResult.entries().isEmpty()) {
                hasMore = mergeResult.hasMore();
                break;
            }
            lastScanned = mergeResult.lastEntry();
            hasMore = mergeResult.hasMore();

            List<FeedInboxEntryVO> filtered = filterTimelineCandidates(
                    userId,
                    "FOLLOW",
                    mergeResult.entries(),
                    followings,
                    true
            );
            for (FeedInboxEntryVO entry : filtered) {
                if (entry == null || entry.getPostId() == null || !acceptedPostIds.add(entry.getPostId())) {
                    continue;
                }
                accepted.add(entry);
                if (accepted.size() >= normalizedLimit) {
                    break;
                }
            }
            rounds++;
            if (accepted.size() >= normalizedLimit || lastScanned == null) {
                break;
            }
            maxIdCursor = new MaxIdCursor(lastScanned.getPublishTimeMs(), lastScanned.getPostId());
        }
        if (accepted.size() < normalizedLimit && rounds >= FOLLOW_SCAN_MAX_ROUNDS && lastScanned != null) {
            hasMore = true;
        }

        List<FeedItemVO> items = accepted.isEmpty()
                ? List.of()
                : feedCardAssembleService.assemble(userId, "FOLLOW", accepted, normalizedLimit);

        FeedInboxEntryVO last = accepted.isEmpty() ? lastScanned : accepted.get(accepted.size() - 1);
        return FeedTimelineVO.builder()
                .items(items)
                .nextCursor(null)
                .nextCursorTs(last == null ? null : last.getPublishTimeMs())
                .nextCursorPostId(last == null ? null : last.getPostId())
                .hasMore(hasMore)
                .build();
    }

    private MergeResult pageAndMergeFollowCandidates(Long userId,
                                                     AuthorBuckets authorBuckets,
                                                     MaxIdCursor maxIdCursor,
                                                     int normalizedLimit) {
        List<FeedInboxEntryVO> inboxEntries = feedTimelineRepository.pageInboxEntries(
                userId, maxIdCursor.cursorTimeMs(), maxIdCursor.cursorPostId(), normalizedLimit
        );

        List<SourceCursor> sources = new ArrayList<>(2 + authorBuckets.bigvAuthors().size());
        sources.add(new SourceCursor(inboxEntries));
        for (Long authorId : authorBuckets.bigvAuthors()) {
            List<FeedInboxEntryVO> timelineEntries = feedAuthorTimelineRepository.pageTimeline(
                    authorId, maxIdCursor.cursorTimeMs(), maxIdCursor.cursorPostId(), normalizedLimit
            );
            sources.add(new SourceCursor(timelineEntries));
        }
        sources.add(new SourceCursor(feedAuthorTimelineRepository.pageTimeline(
                userId, maxIdCursor.cursorTimeMs(), maxIdCursor.cursorPostId(), normalizedLimit
        )));
        return mergeFollowCandidates(sources, normalizedLimit);
    }

    private AuthorBuckets splitAuthorsByCategory(List<Long> followings) {
        if (followings == null || followings.isEmpty()) {
            return new AuthorBuckets(List.of(), List.of());
        }
        Map<Long, Integer> categories = feedAuthorCategoryRepository.batchGetCategory(followings);
        List<Long> normalAuthors = new ArrayList<>(followings.size());
        List<Long> bigvAuthors = new ArrayList<>();
        int threshold = Math.max(0, bigvFollowerThreshold);
        for (Long authorId : followings) {
            if (authorId == null) {
                continue;
            }
            Integer category = categories == null ? null : categories.get(authorId);
            if (category == null) {
                int followerCount = relationRepository.countFollowerIds(authorId);
                int newCategory = (threshold > 0 && followerCount >= threshold)
                        ? FeedAuthorCategoryEnumVO.BIGV.getCode()
                        : FeedAuthorCategoryEnumVO.NORMAL.getCode();
                feedAuthorCategoryRepository.setCategory(authorId, newCategory);
                category = newCategory;
            }
            if (category == FeedAuthorCategoryEnumVO.BIGV.getCode()) {
                bigvAuthors.add(authorId);
            } else {
                normalAuthors.add(authorId);
            }
        }
        return new AuthorBuckets(normalAuthors, bigvAuthors);
    }

    private boolean isPublished(ContentPostEntity post) {
        return post != null && Integer.valueOf(CONTENT_STATUS_PUBLISHED).equals(post.getStatus());
    }

    private MergeResult mergeFollowCandidates(List<SourceCursor> sources, int limit) {
        int normalizedLimit = Math.max(1, limit);
        java.util.PriorityQueue<SourceCursor> queue = new java.util.PriorityQueue<>(
                (left, right) -> entryComparator().compare(left.peek(), right.peek())
        );
        for (SourceCursor source : sources) {
            if (source != null && source.hasNext()) {
                queue.offer(source);
            }
        }
        List<FeedInboxEntryVO> result = new ArrayList<>(normalizedLimit);
        Set<Long> seenPostIds = new HashSet<>();
        while (!queue.isEmpty() && result.size() < normalizedLimit) {
            SourceCursor source = queue.poll();
            FeedInboxEntryVO next = source.pop();
            if (next != null && next.getPostId() != null && next.getPublishTimeMs() != null && seenPostIds.add(next.getPostId())) {
                result.add(next);
            }
            if (source.hasNext()) {
                queue.offer(source);
            }
        }
        boolean hasMore = !queue.isEmpty();
        FeedInboxEntryVO last = result.isEmpty() ? null : result.get(result.size() - 1);
        return new MergeResult(result, last, hasMore);
    }

    private record MaxIdCursor(Long cursorTimeMs, Long cursorPostId) {
    }

    private record AuthorBuckets(List<Long> normalAuthors, List<Long> bigvAuthors) {
    }

    private record MergeResult(List<FeedInboxEntryVO> entries, FeedInboxEntryVO lastEntry, boolean hasMore) {
    }

    private static final class SourceCursor {
        private final Deque<FeedInboxEntryVO> entries;

        private SourceCursor(List<FeedInboxEntryVO> entries) {
            this.entries = new ArrayDeque<>();
            if (entries == null || entries.isEmpty()) {
                return;
            }
            for (FeedInboxEntryVO entry : entries) {
                if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                    continue;
                }
                this.entries.addLast(entry);
            }
        }

        private FeedInboxEntryVO peek() {
            return entries.peekFirst();
        }

        private FeedInboxEntryVO pop() {
            return entries.pollFirst();
        }

        private boolean hasNext() {
            return !entries.isEmpty();
        }
    }
}
