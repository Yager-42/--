package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
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
    private final IObjectCounterService objectCounterService;

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

        // 第三步按登录态补齐用户行为态（liked/faved），不在搜索文档中携带计数字段。
        Set<Long> likedSet = Set.of();
        Set<Long> favedSet = Set.of();
        if (userId != null && !contentIds.isEmpty()) {
            likedSet = loadLikedSet(userId, contentIds);
            favedSet = loadFavedSet(userId, contentIds);
        }

        List<SearchResultVO.SearchItemVO> items = new ArrayList<>(hits.size());
        for (SearchEngineResultVO.SearchHitVO hit : hits) {
            SearchDocumentVO doc = hit == null ? null : hit.getSource();
            if (doc == null || doc.getContentId() == null) {
                continue;
            }
            items.add(SearchResultVO.SearchItemVO.builder()
                    .id(String.valueOf(doc.getContentId()))
                    .authorId(doc.getAuthorId() == null ? null : String.valueOf(doc.getAuthorId()))
                    .title(doc.getTitle())
                    .description(resolveDescription(hit, doc))
                    .coverImage(firstImage(doc.getImgUrls()))
                    .tags(doc.getTags() == null ? List.of() : doc.getTags())
                    .authorAvatar(doc.getAuthorAvatar())
                    .authorNickname(doc.getAuthorNickname())
                    .tagJson(null)
                    .liked(userId != null && likedSet.contains(doc.getContentId()))
                    .faved(userId != null && favedSet.contains(doc.getContentId()))
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

    private Set<Long> loadLikedSet(Long userId, List<Long> contentIds) {
        if (userId == null || contentIds == null || contentIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> likedSet = new LinkedHashSet<>();
        for (Long contentId : contentIds) {
            if (contentId == null) {
                continue;
            }
            if (objectCounterService.isPostLiked(contentId, userId)) {
                likedSet.add(contentId);
            }
        }
        return likedSet;
    }

    private Set<Long> loadFavedSet(Long userId, List<Long> contentIds) {
        if (userId == null || contentIds == null || contentIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> favedSet = new LinkedHashSet<>();
        for (Long contentId : contentIds) {
            if (contentId == null) {
                continue;
            }
            if (objectCounterService.isPostFaved(contentId, userId)) {
                favedSet.add(contentId);
            }
        }
        return favedSet;
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

}
