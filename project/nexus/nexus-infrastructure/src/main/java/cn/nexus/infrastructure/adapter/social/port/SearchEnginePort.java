package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.types.enums.ContentMediaTypeEnumVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 搜索引擎端口实现（官方 ES 8 Java Client）。
 */
@Component
@RequiredArgsConstructor
public class SearchEnginePort implements ISearchEnginePort {

    private final ElasticsearchClient elasticsearchClient;

    @Value("${search.es.indexAlias:social_search}")
    private String indexAlias;

    @Override
    public SearchEngineResultVO search(SearchEngineQueryVO query) {
        if (query == null || query.getKeyword() == null || query.getKeyword().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        try {
            SearchRequest request = buildSearchRequest(query);
            SearchResponse<SearchDocumentVO> resp = elasticsearchClient.search(request, SearchDocumentVO.class);

            long tookMs = resp == null ? 0L : resp.took();
            long totalHits = 0L;
            if (resp != null && resp.hits() != null && resp.hits().total() != null) {
                totalHits = resp.hits().total().value();
            }

            List<SearchEngineResultVO.SearchHitVO> hits = mapHits(resp);
            Map<String, Map<String, Long>> aggs = query.isIncludeFacets() ? mapAggs(resp) : Map.of();

            return SearchEngineResultVO.builder()
                    .tookMs(tookMs)
                    .totalHits(totalHits)
                    .hits(hits)
                    .aggs(aggs)
                    .build();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public void upsert(SearchDocumentVO doc) {
        if (doc == null || doc.getPostId() == null || doc.getEntityIdStr() == null || doc.getEntityIdStr().isBlank()) {
            throw new IllegalArgumentException("SearchDocumentVO invalid");
        }
        String docId = postDocId(doc.getPostId());
        Map<String, Object> body = toIndexBody(doc);

        try {
            IndexRequest<Map<String, Object>> req = IndexRequest.of(i -> i
                    .index(indexAlias)
                    .id(docId)
                    .document(body)
            );
            elasticsearchClient.index(req);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public void delete(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        try {
            DeleteRequest req = DeleteRequest.of(d -> d.index(indexAlias).id(docId));
            elasticsearchClient.delete(req);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public long updateAuthorNickname(Long authorId, String authorNickname) {
        if (authorId == null) {
            throw new IllegalArgumentException("authorId required");
        }
        String nick = authorNickname == null ? "" : authorNickname;
        try {
            UpdateByQueryRequest req = UpdateByQueryRequest.of(u -> u
                    .index(indexAlias)
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.term(t -> t.field("authorId").value(authorId)))
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source("ctx._source.authorNickname=params.nick")
                            .params("nick", JsonData.of(nick))
                    ))
            );
            UpdateByQueryResponse resp = elasticsearchClient.updateByQuery(req);
            return resp == null ? 0L : resp.updated();
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    private SearchRequest buildSearchRequest(SearchEngineQueryVO query) {
        String keyword = query.getKeyword();
        int from = Math.max(0, query.getOffset());
        int size = Math.max(1, query.getLimit());

        Query q = Query.of(root -> root.functionScore(fs -> fs
                .query(boolQuery(keyword, query))
                .functions(List.of(
                        FunctionScore.of(f -> f.gauss(g -> g
                                .field("createTime")
                                .placement(p -> p
                                        .origin(JsonData.of("now"))
                                        .scale(JsonData.of("7d"))
                                        .decay(0.5)
                                )
                        ))
                ))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Sum)
        ));

        SearchRequest.Builder b = new SearchRequest.Builder()
                .index(indexAlias)
                .from(from)
                .size(size)
                .trackTotalHits(t -> t.enabled(true))
                .query(q)
                .highlight(h -> h.fields("contentText", f -> f
                        .numberOfFragments(1)
                        .fragmentSize(80)
                        .preTags("<em>")
                        .postTags("</em>")
                ));

        addSort(b, query.getSort());

        if (query.isIncludeFacets()) {
            b.aggregations("mediaType", a -> a.terms(t -> t.field("mediaType").size(3)));
            b.aggregations("postTypes", a -> a.terms(t -> t.field("postTypes").size(50)));
        }

        return b.build();
    }

    private Query boolQuery(String keyword, SearchEngineQueryVO query) {
        List<Query> must = new ArrayList<>();
        must.add(Query.of(q -> q.multiMatch(mm -> mm
                .query(keyword)
                .type(TextQueryType.BestFields)
                .operator(Operator.And)
                .fields("contentText^3.0", "postTypes^2.0", "authorNickname^1.0")
        )));

        List<Query> should = new ArrayList<>();
        should.add(Query.of(q -> q.term(t -> t
                .field("entityIdStr")
                .value(FieldValue.of(keyword))
                .boost(30.0f)
        )));

        List<Query> filter = new ArrayList<>();
        if (query.getMediaType() != null) {
            filter.add(Query.of(q -> q.term(t -> t.field("mediaType").value(query.getMediaType()))));
        }
        if (query.getPostTypes() != null && !query.getPostTypes().isEmpty()) {
            List<FieldValue> values = new ArrayList<>(query.getPostTypes().size());
            for (String v : query.getPostTypes()) {
                if (v == null || v.isBlank()) {
                    continue;
                }
                values.add(FieldValue.of(v));
            }
            if (!values.isEmpty()) {
                filter.add(Query.of(q -> q.terms(t -> t.field("postTypes").terms(ts -> ts.value(values)))));
            }
        }
        if (query.getTimeFromMs() != null || query.getTimeToMs() != null) {
            filter.add(Query.of(q -> q.range(r -> {
                r.field("createTimeMs");
                if (query.getTimeFromMs() != null) {
                    r.gte(JsonData.of(query.getTimeFromMs()));
                }
                if (query.getTimeToMs() != null) {
                    r.lte(JsonData.of(query.getTimeToMs()));
                }
                return r;
            })));
        }

        return Query.of(q -> q.bool(b -> {
            b.must(must);
            b.should(should);
            if (!filter.isEmpty()) {
                b.filter(filter);
            }
            return b;
        }));
    }

    private void addSort(SearchRequest.Builder b, String sort) {
        String s = sort == null ? "RELEVANT" : sort.trim().toUpperCase(Locale.ROOT);
        if ("LATEST".equals(s)) {
            b.sort(so -> so.field(f -> f.field("createTimeMs").order(SortOrder.Desc)));
            b.sort(so -> so.score(sc -> sc.order(SortOrder.Desc)));
            b.sort(so -> so.field(f -> f.field("postId").order(SortOrder.Desc)));
            return;
        }
        // RELEVANT：score desc -> createTimeMs desc -> postId desc
        b.sort(so -> so.score(sc -> sc.order(SortOrder.Desc)));
        b.sort(so -> so.field(f -> f.field("createTimeMs").order(SortOrder.Desc)));
        b.sort(so -> so.field(f -> f.field("postId").order(SortOrder.Desc)));
    }

    private List<SearchEngineResultVO.SearchHitVO> mapHits(SearchResponse<SearchDocumentVO> resp) {
        if (resp == null || resp.hits() == null || resp.hits().hits() == null || resp.hits().hits().isEmpty()) {
            return List.of();
        }
        List<SearchEngineResultVO.SearchHitVO> res = new ArrayList<>(resp.hits().hits().size());
        for (Hit<SearchDocumentVO> hit : resp.hits().hits()) {
            if (hit == null || hit.source() == null) {
                continue;
            }
            String highlight = null;
            if (hit.highlight() != null) {
                List<String> frags = hit.highlight().get("contentText");
                if (frags != null && !frags.isEmpty()) {
                    highlight = frags.get(0);
                }
            }
            res.add(SearchEngineResultVO.SearchHitVO.builder()
                    .highlightContentText(highlight)
                    .source(hit.source())
                    .build());
        }
        return res;
    }

    private Map<String, Map<String, Long>> mapAggs(SearchResponse<SearchDocumentVO> resp) {
        if (resp == null || resp.aggregations() == null || resp.aggregations().isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Long>> result = new HashMap<>();

        Aggregate media = resp.aggregations().get("mediaType");
        if (media != null) {
            Map<String, Long> buckets = parseTerms(media);
            if (!buckets.isEmpty()) {
                result.put("mediaType", buckets);
            }
        }

        Aggregate postTypes = resp.aggregations().get("postTypes");
        if (postTypes != null) {
            Map<String, Long> buckets = parseTerms(postTypes);
            if (!buckets.isEmpty()) {
                result.put("postTypes", buckets);
            }
        }
        return result;
    }

    private Map<String, Long> parseTerms(Aggregate agg) {
        if (agg.isSterms()) {
            StringTermsAggregate a = agg.sterms();
            if (a == null || a.buckets() == null || a.buckets().array() == null) {
                return Map.of();
            }
            Map<String, Long> map = new HashMap<>();
            for (StringTermsBucket b : a.buckets().array()) {
                if (b == null || b.key() == null) {
                    continue;
                }
                if (b.key().isString()) {
                    map.put(b.key().stringValue(), b.docCount());
                } else {
                    map.put(b.key()._toJsonString(), b.docCount());
                }
            }
            return map;
        }
        if (agg.isLterms()) {
            LongTermsAggregate a = agg.lterms();
            if (a == null || a.buckets() == null || a.buckets().array() == null) {
                return Map.of();
            }
            Map<String, Long> map = new HashMap<>();
            for (LongTermsBucket b : a.buckets().array()) {
                map.put(String.valueOf(b.key()), b.docCount());
            }
            return map;
        }
        return Map.of();
    }

    private Map<String, Object> toIndexBody(SearchDocumentVO doc) {
        long now = System.currentTimeMillis();
        long createTimeMs = doc.getCreateTimeMs() == null ? now : doc.getCreateTimeMs();

        Map<String, Object> m = new HashMap<>();
        m.put("entityIdStr", doc.getEntityIdStr());
        m.put("createTimeMs", createTimeMs);
        // createTime 是 date 类型：直接写 epoch ms
        m.put("createTime", createTimeMs);

        m.put("postId", doc.getPostId());
        m.put("authorId", doc.getAuthorId());
        m.put("authorNickname", doc.getAuthorNickname() == null ? "" : doc.getAuthorNickname());
        m.put("contentText", doc.getContentText() == null ? "" : doc.getContentText());
        m.put("postTypes", doc.getPostTypes() == null ? List.of() : doc.getPostTypes());
        m.put("mediaType", doc.getMediaType() == null ? ContentMediaTypeEnumVO.TEXT.getCode() : doc.getMediaType());
        return m;
    }

    private String postDocId(Long postId) {
        return "POST:" + postId;
    }
}
