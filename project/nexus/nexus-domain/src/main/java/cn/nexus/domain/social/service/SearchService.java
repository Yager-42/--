package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * SearchService 服务实现。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Service
@RequiredArgsConstructor
public class SearchService implements ISearchService {

    private final ISearchEnginePort searchEnginePort;
    private final IFeedCardStatRepository feedCardStatRepository;
    private final IObjectCounterPort objectCounterPort;
    private final IReactionRepository reactionRepository;

    /**
     * 执行搜索。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param keyword keyword 参数。类型：{@link String}
     * @param size size 参数。类型：{@link Integer}
     * @param tags tags 参数。类型：{@link String}
     * @param after after 参数。类型：{@link String}
     * @return 处理结果。类型：{@link SearchResultVO}
     */
    @Override
    public SearchResultVO search(Long userId, String keyword, Integer size, String tags, String after) {
        // 第一步先把输入收口，避免把空白关键词、非法 size 直接透传到 ES。
        String normalizedKeyword = normalizeRequiredText(keyword, "q 不能为空");
        int limit = normalizeSize(size, 20);
        List<String> tagList = parseTags(tags);

        // 第二步只把“搜索表达”交给搜索引擎端口，领域服务不直接拼底层 ES 细节。
        SearchEngineResultVO engine = searchEnginePort.search(SearchEngineQueryVO.builder()
                .keyword(normalizedKeyword)
                .limit(limit)
                .tags(tagList)
                .after(after)
                .build());

        List<SearchEngineResultVO.SearchHitVO> hits = engine == null || engine.getHits() == null ? List.of() : engine.getHits();
        List<Long> contentIds = new ArrayList<>(hits.size());
        for (SearchEngineResultVO.SearchHitVO hit : hits) {
            SearchDocumentVO doc = hit == null ? null : hit.getSource();
            if (doc != null && doc.getContentId() != null) {
                contentIds.add(doc.getContentId());
            }
        }

        // 第三步用缓存计数 + 批量点赞态把“索引快照”补成更接近实时的展示结果。
        Map<Long, FeedCardStatVO> statMap = loadLikeStats(contentIds);
        Set<Long> likedSet = Set.of();
        if (userId != null && !contentIds.isEmpty()) {
            ReactionTargetVO template = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(0L)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            likedSet = reactionRepository.batchExists(template, userId, contentIds);
        }

        List<SearchResultVO.SearchItemVO> items = new ArrayList<>(hits.size());
        for (SearchEngineResultVO.SearchHitVO hit : hits) {
            SearchDocumentVO doc = hit == null ? null : hit.getSource();
            if (doc == null || doc.getContentId() == null) {
                continue;
            }
            FeedCardStatVO stat = statMap == null ? null : statMap.get(doc.getContentId());
            Long likeCount = stat == null ? safeLong(doc.getLikeCount()) : safeLong(stat.getLikeCount());
            items.add(SearchResultVO.SearchItemVO.builder()
                    .id(String.valueOf(doc.getContentId()))
                    .title(doc.getTitle())
                    .description(resolveDescription(hit, doc))
                    .coverImage(firstImage(doc.getImgUrls()))
                    .tags(doc.getTags() == null ? List.of() : doc.getTags())
                    .authorAvatar(doc.getAuthorAvatar())
                    .authorNickname(doc.getAuthorNickname())
                    .tagJson(null)
                    .likeCount(likeCount)
                    .favoriteCount(0L)
                    .liked(userId != null && likedSet.contains(doc.getContentId()))
                    .faved(false)
                    .isTop(null)
                    .build());
        }

        return SearchResultVO.builder()
                .items(items)
                .nextAfter(engine == null ? null : engine.getNextAfter())
                .hasMore(engine != null && engine.isHasMore())
                .build();
    }

    /**
     * 查询联想词。
     *
     * @param prefix prefix 参数。类型：{@link String}
     * @param size size 参数。类型：{@link Integer}
     * @return 处理结果。类型：{@link SearchSuggestVO}
     */
    @Override
    public SearchSuggestVO suggest(String prefix, Integer size) {
        String normalizedPrefix = normalizeRequiredText(prefix, "prefix 不能为空");
        int limit = normalizeSize(size, 10);
        return SearchSuggestVO.builder()
                .items(searchEnginePort.suggest(normalizedPrefix, limit))
                .build();
    }

    private String resolveDescription(SearchEngineResultVO.SearchHitVO hit, SearchDocumentVO doc) {
        String titleHighlight = hit == null ? null : trimToNull(hit.getHighlightTitle());
        String bodyHighlight = hit == null ? null : trimToNull(hit.getHighlightBody());
        if (titleHighlight != null && bodyHighlight != null) {
            return titleHighlight + " " + bodyHighlight;
        }
        if (titleHighlight != null) {
            return titleHighlight;
        }
        if (bodyHighlight != null) {
            return bodyHighlight;
        }
        return doc == null ? null : doc.getDescription();
    }

    private String firstImage(List<String> imgUrls) {
        if (imgUrls == null || imgUrls.isEmpty()) {
            return null;
        }
        for (String imgUrl : imgUrls) {
            String normalized = trimToNull(imgUrl);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private Map<Long, FeedCardStatVO> loadLikeStats(List<Long> contentIds) {
        Map<Long, FeedCardStatVO> cached = feedCardStatRepository.getBatch(contentIds);
        Map<Long, FeedCardStatVO> statMap = new java.util.HashMap<>(cached == null ? Map.of() : cached);
        if (contentIds == null || contentIds.isEmpty()) {
            return statMap;
        }
        List<FeedCardStatVO> toSave = new ArrayList<>();
        for (Long contentId : contentIds) {
            if (contentId == null || statMap.containsKey(contentId)) {
                continue;
            }
            // 这里只有缓存 miss 才回真相源，避免每次搜索命中都把互动库打成热点。
            long count = objectCounterPort.getCount(ObjectCounterTarget.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(contentId)
                    .counterType(ObjectCounterType.LIKE)
                    .build());
            FeedCardStatVO stat = FeedCardStatVO.builder()
                    .postId(contentId)
                    .likeCount(Math.max(0L, count))
                    .build();
            statMap.put(contentId, stat);
            toSave.add(stat);
        }
        if (!toSave.isEmpty()) {
            feedCardStatRepository.saveBatch(toSave);
        }
        return statMap;
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String part : tags.split(",")) {
            String normalized = trimToNull(part);
            if (normalized != null) {
                dedup.add(normalized);
            }
        }
        return dedup.isEmpty() ? List.of() : new ArrayList<>(dedup);
    }

    private String normalizeRequiredText(String raw, String message) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
        }
        return normalized.replaceAll("\\s+", " ");
    }

    private int normalizeSize(Integer size, int defaultValue) {
        if (size == null) {
            return defaultValue;
        }
        if (size < 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return size;
    }

    private String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }
}
