package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.ISearchHistoryRepository;
import cn.nexus.domain.social.adapter.repository.ISearchTrendingRepository;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.domain.social.model.valobj.SearchTrendingVO;
import cn.nexus.types.enums.ContentMediaTypeEnumVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 搜索服务实现。
 */
@Service
@RequiredArgsConstructor
public class SearchService implements ISearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final Pattern SPACE_COMPRESS = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;
    private final ISearchEnginePort searchEnginePort;
    private final ISearchHistoryRepository searchHistoryRepository;
    private final ISearchTrendingRepository searchTrendingRepository;

    @Value("${search.result.defaultLimit:20}")
    private int defaultLimit;

    @Value("${search.result.maxLimit:50}")
    private int maxLimit;

    @Value("${search.trending.topKForSuggestScan:200}")
    private int topKForSuggestScan;

    @Override
    public SearchResultVO search(Long userId, String keyword, String type, String sort, String filters) {
        long startNs = System.nanoTime();

        Filters parsed = parseAndValidateFilters(filters);
        String normalizedType = normalizeType(type);

        if ("UNSUPPORTED".equals(normalizedType)) {
            SearchResultVO vo = SearchResultVO.builder()
                    .items(List.of())
                    .facets(buildFacetsJson("UNSUPPORTED_TYPE", normalizedType, parsed.offset, parsed.limit, Map.of()))
                    .build();
            logGeneral(userId, normalizeKeyword(keyword), normalizedType, normalizeSort(sort),
                    parsed.offset, parsed.limit, 0L, 0L, 0, parsed.includeFacets, startNs);
            return vo;
        }

        String normalizedKeyword = normalizeKeyword(keyword);
        if (normalizedKeyword.isEmpty()) {
            SearchResultVO vo = SearchResultVO.builder()
                    .items(List.of())
                    .facets(buildFacetsJson("EMPTY_KEYWORD", normalizedType, parsed.offset, parsed.limit, Map.of()))
                    .build();
            logGeneral(userId, normalizedKeyword, normalizedType, normalizeSort(sort),
                    parsed.offset, parsed.limit, 0L, 0L, 0, parsed.includeFacets, startNs);
            return vo;
        }

        String normalizedSort = normalizeSort(sort);
        SearchEngineQueryVO query = SearchEngineQueryVO.builder()
                .keyword(normalizedKeyword)
                .sort(normalizedSort)
                .offset(parsed.offset)
                .limit(parsed.limit)
                .includeFacets(parsed.includeFacets)
                .mediaType(parsed.mediaType)
                .postTypes(parsed.postTypes)
                .timeFromMs(parsed.timeFromMs)
                .timeToMs(parsed.timeToMs)
                .build();

        SearchEngineResultVO es;
        try {
            es = searchEnginePort.search(query);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }

        List<SearchResultVO.SearchItemVO> items = mapHitsToItems(es);
        Map<String, Map<String, Long>> aggs = parsed.includeFacets && es.getAggs() != null ? es.getAggs() : Map.of();
        String facets = buildFacetsJson("OK", normalizedType, parsed.offset, parsed.limit, aggs);

        // ES 查询成功后，才 best-effort 写入历史/热搜。
        if (userId != null) {
            bestEffortHistoryAndTrendWrite(userId, normalizedKeyword, "POST");
        }

        logGeneral(userId, normalizedKeyword, normalizedType, normalizedSort,
                parsed.offset, parsed.limit,
                es == null ? 0L : es.getTookMs(),
                es == null ? 0L : es.getTotalHits(),
                items == null ? 0 : items.size(),
                parsed.includeFacets,
                startNs);

        return SearchResultVO.builder().items(items).facets(facets).build();
    }

    @Override
    public SearchSuggestVO suggest(Long userId, String keyword) {
        long startNs = System.nanoTime();
        String normalized = normalizeKeyword(keyword);

        List<String> suggestions;
        try {
            if (normalized.isEmpty()) {
                suggestions = searchTrendingRepository.top("POST", 10);
            } else {
                int scanTopK = Math.max(1, topKForSuggestScan);
                suggestions = searchTrendingRepository.topAndFilterPrefix("POST", normalized, scanTopK, 10);
            }
        } catch (Exception e) {
            log.warn("event=search.suggest.redis_fail userId={} keyword=\"{}\" err={}", userId, normalized, e.toString());
            suggestions = List.of();
        }

        long costMs = costMs(startNs);
        log.info("event=search.suggest userId={} keyword=\"{}\" returned={} costMs={}",
                userId, normalized, suggestions == null ? 0 : suggestions.size(), costMs);

        return SearchSuggestVO.builder().suggestions(suggestions).build();
    }

    @Override
    public SearchTrendingVO trending(String category) {
        long startNs = System.nanoTime();
        String normalizedCategory = normalizeTrendingCategory(category);

        List<String> keywords;
        try {
            keywords = searchTrendingRepository.top(normalizedCategory, 10);
        } catch (Exception e) {
            log.warn("event=search.trending.redis_fail category={} err={}", normalizedCategory, e.toString());
            keywords = List.of();
        }

        long costMs = costMs(startNs);
        log.info("event=search.trending category={} returned={} costMs={}",
                normalizedCategory, keywords == null ? 0 : keywords.size(), costMs);

        return SearchTrendingVO.builder().keywords(keywords).build();
    }

    @Override
    public OperationResultVO clearHistory(Long userId) {
        long startNs = System.nanoTime();
        boolean ok = true;
        try {
            searchHistoryRepository.clear(userId);
        } catch (Exception e) {
            ok = false;
            log.warn("event=search.history.clear.redis_fail userId={} err={}", userId, e.toString());
        }

        long costMs = costMs(startNs);
        log.info("event=search.history.clear userId={} success={} costMs={}", userId, ok, costMs);

        return OperationResultVO.builder()
                .success(ok)
                .id(userId)
                .status(ok ? "CLEARED" : "FAILED")
                .message(ok ? "搜索历史已清空" : "搜索历史清空失败")
                .build();
    }

    private void bestEffortHistoryAndTrendWrite(Long userId, String keyword, String category) {
        try {
            searchHistoryRepository.record(userId, keyword);
        } catch (Exception e) {
            log.warn("event=search.redis.fail op=history userId={} keyword=\"{}\" category={} err={}",
                    userId, keyword, category, e.toString());
        }
        try {
            searchTrendingRepository.incr(category, keyword);
        } catch (Exception e) {
            log.warn("event=search.redis.fail op=trend userId={} keyword=\"{}\" category={} err={}",
                    userId, keyword, category, e.toString());
        }
    }

    private List<SearchResultVO.SearchItemVO> mapHitsToItems(SearchEngineResultVO es) {
        if (es == null || es.getHits() == null || es.getHits().isEmpty()) {
            return List.of();
        }
        List<SearchResultVO.SearchItemVO> res = new java.util.ArrayList<>(es.getHits().size());
        for (SearchEngineResultVO.SearchHitVO hit : es.getHits()) {
            if (hit == null || hit.getSource() == null || hit.getSource().getPostId() == null) {
                continue;
            }
            String content = safeString(hit.getSource().getContentText());
            String title = firstLine(content);
            if (title.length() > 30) {
                title = title.substring(0, 30);
            }

            String summary;
            if (hit.getHighlightContentText() != null && !hit.getHighlightContentText().isBlank()) {
                summary = hit.getHighlightContentText();
            } else {
                summary = content;
                if (summary.length() > 80) {
                    summary = summary.substring(0, 80);
                }
            }

            res.add(SearchResultVO.SearchItemVO.builder()
                    .id(String.valueOf(hit.getSource().getPostId()))
                    .type("POST")
                    .title(title)
                    .summary(summary)
                    .build());
        }
        return res;
    }

    private String buildFacetsJson(String reason, String normalizedType, int offset, int limit, Map<String, Map<String, Long>> aggs) {
        try {
            Map<String, Object> meta = Map.of(
                    "reason", reason,
                    "normalizedType", normalizedType,
                    "offset", offset,
                    "limit", limit
            );
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("meta", meta);
            root.put("mediaType", aggs == null ? Map.of() : aggs.getOrDefault("mediaType", Map.of()));
            root.put("postTypes", aggs == null ? Map.of() : aggs.getOrDefault("postTypes", Map.of()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // facets 只是体验字段：序列化失败不影响主链路，但必须可解析为字符串。
            return "{\"meta\":{\"reason\":\"" + safeString(reason) + "\",\"normalizedType\":\"" + safeString(normalizedType) +
                    "\",\"offset\":" + offset + ",\"limit\":" + limit + "},\"mediaType\":{},\"postTypes\":{}}";
        }
    }

    private void logGeneral(Long userId, String keyword, String type, String sort,
                            int offset, int limit,
                            long esTookMs, long totalHits, int returned, boolean facetsEnabled, long startNs) {
        long costMs = costMs(startNs);
        log.info("event=search.general userId={} keyword=\"{}\" type={} sort={} offset={} limit={} esTookMs={} totalHits={} returned={} costMs={} facetsEnabled={}",
                userId,
                keyword,
                type,
                sort,
                offset,
                limit,
                esTookMs,
                totalHits,
                returned,
                costMs,
                facetsEnabled);
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private Filters parseAndValidateFilters(String raw) {
        int normalizedDefaultLimit = Math.max(1, defaultLimit);
        int normalizedMaxLimit = Math.max(1, maxLimit);

        String filters = raw == null ? "" : raw.trim();
        if (filters.isEmpty()) {
            return Filters.defaults(normalizedDefaultLimit);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(filters);
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo(), e);
        }

        if (node == null || !node.isObject()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        int offset = intField(node, "offset", 0);
        if (offset < 0 || offset > 2000) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        int limit = intField(node, "limit", normalizedDefaultLimit);
        if (limit < 1 || limit > normalizedMaxLimit) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        Integer mediaType = null;
        if (node.has("mediaType") && !node.get("mediaType").isNull()) {
            mediaType = intField(node, "mediaType", -1);
            if (mediaType != ContentMediaTypeEnumVO.TEXT.getCode()
                    && mediaType != ContentMediaTypeEnumVO.IMAGE.getCode()
                    && mediaType != ContentMediaTypeEnumVO.VIDEO.getCode()) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
        }

        List<String> postTypes = null;
        if (node.has("postTypes") && !node.get("postTypes").isNull()) {
            JsonNode arr = node.get("postTypes");
            if (!arr.isArray()) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
            List<String> tmp = new java.util.ArrayList<>();
            for (JsonNode it : arr) {
                if (it == null || it.isNull()) {
                    continue;
                }
                if (!it.isTextual()) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
                }
                String v = it.asText().trim();
                if (v.isEmpty()) {
                    continue;
                }
                if (tmp.contains(v)) {
                    continue;
                }
                tmp.add(v);
                if (tmp.size() > 5) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
                }
            }
            if (!tmp.isEmpty()) {
                postTypes = tmp;
            }
        }

        Long fromMs = null;
        Long toMs = null;
        if (node.has("timeRange") && !node.get("timeRange").isNull()) {
            JsonNode tr = node.get("timeRange");
            if (tr != null && tr.isObject()) {
                long f = longField(tr, "fromMs", 0L);
                long t = longField(tr, "toMs", 0L);
                if (f < 0 || t < 0) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
                }
                if (f > 0) {
                    fromMs = f;
                }
                if (t > 0) {
                    toMs = t;
                }
                if (fromMs != null && toMs != null && fromMs > toMs) {
                    throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
                }
            } else {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
        }

        String category = "AUTO";
        if (node.has("category") && !node.get("category").isNull()) {
            JsonNode c = node.get("category");
            if (!c.isTextual()) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
            category = c.asText().trim();
        }
        String catUpper = category.toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(catUpper) && !"POST".equals(catUpper) && !"ALL".equals(catUpper)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        boolean includeFacets = true;
        if (node.has("includeFacets") && !node.get("includeFacets").isNull()) {
            JsonNode b = node.get("includeFacets");
            if (!b.isBoolean()) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
            }
            includeFacets = b.asBoolean();
        }

        return new Filters(offset, limit, postTypes, mediaType, fromMs, toMs, catUpper, includeFacets);
    }

    private int intField(JsonNode node, String key, int defaultValue) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return defaultValue;
        }
        if (!v.canConvertToInt()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return v.asInt();
    }

    private long longField(JsonNode node, String key, long defaultValue) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return defaultValue;
        }
        if (!v.canConvertToLong()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        return v.asLong();
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String compact = SPACE_COMPRESS.matcher(trimmed).replaceAll(" ");
        String lowered = compact.toLowerCase(Locale.ROOT);
        if (lowered.length() > 64) {
            lowered = lowered.substring(0, 64);
        }
        return lowered;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "POST";
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(t) || "POST".equals(t)) {
            return "POST";
        }
        return "UNSUPPORTED";
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "RELEVANT";
        }
        String s = sort.trim().toUpperCase(Locale.ROOT);
        if ("LATEST".equals(s)) {
            return "LATEST";
        }
        return "RELEVANT";
    }

    private String normalizeTrendingCategory(String category) {
        if (category == null || category.isBlank()) {
            return "POST";
        }
        String c = category.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(c) || "POST".equals(c)) {
            return "POST";
        }
        return "POST";
    }

    private String safeString(String s) {
        return s == null ? "" : s;
    }

    private String firstLine(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String oneLine = content.replace("\r", "\n");
        int idx = oneLine.indexOf('\n');
        if (idx >= 0) {
            oneLine = oneLine.substring(0, idx);
        }
        return oneLine.trim();
    }

    private static final class Filters {
        private final int offset;
        private final int limit;
        private final List<String> postTypes;
        private final Integer mediaType;
        private final Long timeFromMs;
        private final Long timeToMs;
        private final String category;
        private final boolean includeFacets;

        private Filters(int offset, int limit, List<String> postTypes, Integer mediaType,
                        Long timeFromMs, Long timeToMs, String category, boolean includeFacets) {
            this.offset = offset;
            this.limit = limit;
            this.postTypes = postTypes;
            this.mediaType = mediaType;
            this.timeFromMs = timeFromMs;
            this.timeToMs = timeToMs;
            this.category = category;
            this.includeFacets = includeFacets;
        }

        private static Filters defaults(int defaultLimit) {
            return new Filters(0, defaultLimit, null, null, null, null, "AUTO", true);
        }
    }
}
