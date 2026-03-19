package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
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

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int CANDIDATE_FACTOR = 3;
    private static final int FOLLOW_SEEN_TTL_DAYS = 14;
    private static final int RELATION_BLOCK = 3;

    private final IContentRepository contentRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedOutboxRepository feedOutboxRepository;
    private final IFeedBigVPoolRepository feedBigVPoolRepository;
    private final IFeedFollowSeenRepository feedFollowSeenRepository;
    private final IFeedInboxRebuildService feedInboxRebuildService;
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
    public FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType) {
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

        boolean homePage = cursor == null || cursor.isBlank();
        boolean rebuilt = false;
        if (homePage) {
            rebuilt = feedInboxRebuildService.rebuildIfNeeded(userId);
        }

        MaxIdCursor maxIdCursor = homePage ? new MaxIdCursor(null, null) : resolveMaxIdCursor(cursor);
        if (!homePage && maxIdCursor == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }

        int scanFactor = homePage ? CANDIDATE_FACTOR * 2 : CANDIDATE_FACTOR;
        int scanLimit = normalizedLimit * Math.max(1, scanFactor);
        List<FeedInboxEntryVO> inboxCandidates = feedTimelineRepository.pageInboxEntries(
                userId, maxIdCursor.cursorTimeMs(), maxIdCursor.cursorPostId(), scanLimit
        );

        if (homePage && rebuilt) {
            List<FeedInboxEntryVO> candidates = inboxCandidates == null ? List.of() : inboxCandidates;
            candidates = filterFollowSeenCandidates(userId, candidates);
            String nextCursor = candidates.isEmpty() || candidates.get(candidates.size() - 1).getPostId() == null
                    ? null
                    : candidates.get(candidates.size() - 1).getPostId().toString();
            List<FeedItemVO> items = buildTimelineItems(userId, source, candidates, normalizedLimit, null, false);
            markFollowSeen(userId, items);
            return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
        }

        List<Long> followings = listFollowings(userId);
        List<FeedInboxEntryVO> bigvCandidates = listBigVCandidates(userId, followings, maxIdCursor, scanLimit, normalizedLimit);
        List<FeedInboxEntryVO> candidates = mergeAndDedup(inboxCandidates, bigvCandidates, scanLimit);
        if (homePage) {
            candidates = filterFollowSeenCandidates(userId, candidates);
        }

        String nextCursor = candidates.isEmpty() || candidates.get(candidates.size() - 1).getPostId() == null
                ? null
                : candidates.get(candidates.size() - 1).getPostId().toString();

        List<FeedItemVO> items = buildTimelineItems(userId, source, candidates, normalizedLimit, followings, true);
        markFollowSeen(userId, items);
        return FeedTimelineVO.builder().items(items).nextCursor(nextCursor).build();
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

            List<Long> poolCandidates = listRecommendBigvPoolCandidates(followings, batch);
            if (!poolCandidates.isEmpty()) {
                feedRecommendSessionRepository.appendCandidates(userId, sessionId, poolCandidates);
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
            List<FeedInboxEntryVO> entries = feedOutboxRepository.pageOutbox(authorId, null, null, 1);
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

    private List<Long> listRecommendBigvPoolCandidates(List<Long> followings, int batch) {
        if (followings == null || followings.isEmpty()) {
            return List.of();
        }
        if (!shouldUseBigVPool(followings)) {
            return List.of();
        }
        int buckets = Math.max(1, bigvPoolBuckets);
        int perBucket = Math.max(1, batch / buckets);
        List<Long> ids = new ArrayList<>(batch);
        for (int i = 0; i < buckets; i++) {
            List<FeedInboxEntryVO> entries = feedBigVPoolRepository.pagePool(i, null, null, perBucket);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (FeedInboxEntryVO entry : entries) {
                if (entry != null && entry.getPostId() != null) {
                    ids.add(entry.getPostId());
                }
                if (ids.size() >= batch) {
                    return ids;
                }
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

        List<Long> mergedFollowings = followings;
        boolean homePage = cursor != null && cursor.cursorTimeMs() == null && cursor.cursorPostId() == null;
        if (homePage && followings.size() == maxFollowings) {
            int limit = Math.max(0, maxBigvFollowings);
            List<Long> bigvFollowings = relationRepository.listBigVFollowingIds(userId, bigvFollowerThreshold, limit);
            if (bigvFollowings != null && !bigvFollowings.isEmpty()) {
                Set<Long> seen = new HashSet<>(followings);
                List<Long> merged = new ArrayList<>(followings.size() + bigvFollowings.size());
                merged.addAll(followings);
                for (Long id : bigvFollowings) {
                    if (id == null || id.equals(userId)) {
                        continue;
                    }
                    if (seen.add(id)) {
                        merged.add(id);
                    }
                }
                mergedFollowings = merged;
            }
        }

        if (shouldUseBigVPool(mergedFollowings)) {
            return listPoolCandidates(userId, mergedFollowings, cursor, normalizedLimit);
        }
        List<Long> bigvAuthors = pickBigVAuthors(mergedFollowings);
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
        Map<Long, Integer> categories = feedAuthorCategoryRepository.batchGetCategory(followings);
        List<Long> bigv = new ArrayList<>(Math.min(followings.size(), limit));
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
                bigv.add(authorId);
            }
            if (bigv.size() >= limit) {
                break;
            }
        }
        return bigv.isEmpty() ? List.of() : bigv;
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

    private List<FeedItemVO> buildTimelineItems(Long userId,
                                                String source,
                                                List<FeedInboxEntryVO> candidates,
                                                int normalizedLimit,
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
        return feedCardAssembleService.assemble(userId, source, filtered, normalizedLimit);
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

    private List<FeedInboxEntryVO> filterFollowSeenCandidates(Long userId, List<FeedInboxEntryVO> candidates) {
        if (userId == null || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<FeedInboxEntryVO> filtered = new ArrayList<>(candidates.size());
        for (FeedInboxEntryVO entry : candidates) {
            if (entry == null || entry.getPostId() == null) {
                continue;
            }
            try {
                if (feedFollowSeenRepository.isSeen(userId, entry.getPostId())) {
                    continue;
                }
            } catch (Exception e) {
                // best-effort：读取失败不影响主链路
                log.warn("feed follow seen check failed, userId={}, postId={}", userId, entry.getPostId(), e);
            }
            filtered.add(entry);
        }
        return filtered;
    }

    private void markFollowSeen(Long userId, List<FeedItemVO> items) {
        if (userId == null || items == null || items.isEmpty()) {
            return;
        }
        boolean touched = false;
        for (FeedItemVO item : items) {
            if (item == null || item.getPostId() == null) {
                continue;
            }
            try {
                feedFollowSeenRepository.markSeen(userId, item.getPostId());
                touched = true;
            } catch (Exception e) {
                // best-effort：失败不影响主链路
                log.warn("feed follow mark seen failed, userId={}, postId={}", userId, item.getPostId(), e);
            }
        }
        if (!touched) {
            return;
        }
        try {
            feedFollowSeenRepository.expire(userId, FOLLOW_SEEN_TTL_DAYS);
        } catch (Exception e) {
            log.warn("feed follow seen expire failed, userId={}", userId, e);
        }
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

    private record MaxIdCursor(Long cursorTimeMs, Long cursorPostId) {
    }
}
