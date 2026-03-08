package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchEnginePort implements ISearchEnginePort {

    private final RestClient searchRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${search.es.indexAlias:social_search}")
    private String indexAlias;

    @Override
    public SearchEngineResultVO search(SearchEngineQueryVO query) {
        if (query == null || query.getKeyword() == null || query.getKeyword().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        try {
            Request request = new Request("POST", "/" + indexAlias + "/_search");
            request.setJsonEntity(buildSearchRequestBody(query).toString());
            Response response = searchRestClient.performRequest(request);
            JsonNode root = objectMapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            return parseSearchResponse(root, query.getLimit());
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public List<String> suggest(String prefix, int limit) {
        try {
            Request request = new Request("POST", "/" + indexAlias + "/_search");
            request.setJsonEntity(buildSuggestRequestBody(prefix, limit).toString());
            Response response = searchRestClient.performRequest(request);
            JsonNode root = objectMapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            return parseSuggestResponse(root);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public void upsert(SearchDocumentVO doc) {
        if (doc == null || doc.getContentId() == null) {
            throw new IllegalArgumentException("SearchDocumentVO invalid");
        }
        try {
            Request request = new Request("PUT", "/" + indexAlias + "/_doc/" + doc.getContentId());
            request.addParameter("refresh", "wait_for");
            request.setJsonEntity(toIndexBody(doc).toString());
            searchRestClient.performRequest(request);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public void softDelete(Long contentId) {
        if (contentId == null) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode doc = body.putObject("doc");
            doc.put("content_id", contentId);
            doc.put("status", "deleted");
            body.put("doc_as_upsert", true);

            Request request = new Request("POST", "/" + indexAlias + "/_update/" + contentId);
            request.addParameter("refresh", "wait_for");
            request.setJsonEntity(body.toString());
            searchRestClient.performRequest(request);
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    @Override
    public long updateAuthorNickname(Long authorId, String authorNickname) {
        if (authorId == null) {
            return 0L;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode script = body.putObject("script");
            script.put("source", "ctx._source.author_nickname = params.nickname");
            ObjectNode params = script.putObject("params");
            params.put("nickname", authorNickname == null ? "" : authorNickname);

            ObjectNode query = body.putObject("query");
            ObjectNode term = query.putObject("term");
            term.put("author_id", authorId);

            Request request = new Request("POST", "/" + indexAlias + "/_update_by_query");
            request.addParameter("conflicts", "proceed");
            request.addParameter("refresh", "true");
            request.setJsonEntity(body.toString());
            Response response = searchRestClient.performRequest(request);
            JsonNode root = objectMapper.readTree(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
            JsonNode updated = root.get("updated");
            return updated == null || updated.isNull() ? 0L : updated.asLong();
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    private ObjectNode buildSearchRequestBody(SearchEngineQueryVO query) {
        int limit = Math.max(1, query.getLimit());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("track_total_hits", true);
        root.put("size", limit + 1);

        ObjectNode functionScore = root.putObject("query").putObject("function_score");
        ObjectNode bool = functionScore.putObject("query").putObject("bool");
        ArrayNode must = bool.putArray("must");
        ObjectNode multiMatch = must.addObject().putObject("multi_match");
        multiMatch.put("query", query.getKeyword());
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("title^3");
        fields.add("body");

        ArrayNode filter = bool.putArray("filter");
        filter.addObject().putObject("term").put("status", "published");
        if (query.getTags() != null && !query.getTags().isEmpty()) {
            ArrayNode values = objectMapper.createArrayNode();
            for (String tag : query.getTags()) {
                if (tag != null && !tag.isBlank()) {
                    values.add(tag);
                }
            }
            if (!values.isEmpty()) {
                filter.addObject().putObject("terms").set("tags", values);
            }
        }

        ArrayNode functions = functionScore.putArray("functions");
        ObjectNode likeScore = functions.addObject();
        likeScore.putObject("field_value_factor")
                .put("field", "like_count")
                .put("modifier", "log1p")
                .put("missing", 0.0D);
        likeScore.put("weight", 2.0D);

        ObjectNode viewScore = functions.addObject();
        viewScore.putObject("field_value_factor")
                .put("field", "view_count")
                .put("modifier", "log1p")
                .put("missing", 0.0D);
        viewScore.put("weight", 1.0D);
        functionScore.put("score_mode", "sum");
        functionScore.put("boost_mode", "sum");

        ObjectNode highlight = root.putObject("highlight");
        ArrayNode preTags = highlight.putArray("pre_tags");
        preTags.add("<em>");
        ArrayNode postTags = highlight.putArray("post_tags");
        postTags.add("</em>");
        ObjectNode highlightFields = highlight.putObject("fields");
        highlightFields.putObject("title");
        highlightFields.putObject("body");

        ArrayNode sort = root.putArray("sort");
        sort.addObject().putObject("_score").put("order", "desc");
        sort.addObject().putObject("publish_time").put("order", "desc");
        sort.addObject().putObject("like_count").put("order", "desc");
        sort.addObject().putObject("view_count").put("order", "desc");
        sort.addObject().putObject("content_id").put("order", "desc");

        ArrayNode after = decodeAfter(query.getAfter());
        if (after != null && !after.isEmpty()) {
            root.set("search_after", after);
        }
        return root;
    }

    private ObjectNode buildSuggestRequestBody(String prefix, int limit) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", 0);
        ObjectNode suggest = root.putObject("suggest");
        ObjectNode titleSuggest = suggest.putObject("title_suggest");
        titleSuggest.put("prefix", prefix);
        titleSuggest.putObject("completion")
                .put("field", "title_suggest")
                .put("size", Math.max(1, limit))
                .put("skip_duplicates", true);
        return root;
    }

    private SearchEngineResultVO parseSearchResponse(JsonNode root, int limit) {
        JsonNode hitsNode = root == null ? null : root.path("hits").path("hits");
        List<SearchEngineResultVO.SearchHitVO> hits = new ArrayList<>();
        String nextAfter = null;
        boolean hasMore = false;
        if (hitsNode != null && hitsNode.isArray()) {
            int size = hitsNode.size();
            hasMore = size > limit;
            int upper = Math.min(size, limit);
            for (int i = 0; i < upper; i++) {
                JsonNode hit = hitsNode.get(i);
                SearchDocumentVO source = mapSource(hit.path("_source"));
                hits.add(SearchEngineResultVO.SearchHitVO.builder()
                        .highlightTitle(firstHighlight(hit, "title"))
                        .highlightBody(firstHighlight(hit, "body"))
                        .source(source)
                        .build());
            }
            if (hasMore && upper > 0) {
                JsonNode lastVisible = hitsNode.get(upper - 1);
                nextAfter = encodeAfter(lastVisible.path("sort"));
            }
        }
        return SearchEngineResultVO.builder()
                .hits(hits)
                .nextAfter(nextAfter)
                .hasMore(hasMore)
                .build();
    }

    private List<String> parseSuggestResponse(JsonNode root) {
        List<String> items = new ArrayList<>();
        JsonNode suggest = root == null ? null : root.path("suggest").path("title_suggest");
        if (suggest == null || !suggest.isArray() || suggest.isEmpty()) {
            return items;
        }
        JsonNode options = suggest.get(0).path("options");
        if (!options.isArray()) {
            return items;
        }
        for (JsonNode option : options) {
            String text = safeText(option.get("text"));
            if (text != null && !items.contains(text)) {
                items.add(text);
            }
        }
        return items;
    }

    private SearchDocumentVO mapSource(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            return null;
        }
        return SearchDocumentVO.builder()
                .contentId(safeLong(source.get("content_id")))
                .contentType(safeText(source.get("content_type")))
                .title(safeText(source.get("title")))
                .description(safeText(source.get("description")))
                .body(safeText(source.get("body")))
                .tags(stringList(source.get("tags")))
                .authorId(safeLong(source.get("author_id")))
                .authorAvatar(safeText(source.get("author_avatar")))
                .authorNickname(safeText(source.get("author_nickname")))
                .authorTagJson(safeText(source.get("author_tag_json")))
                .publishTime(safeLong(source.get("publish_time")))
                .likeCount(safeLong(source.get("like_count")))
                .favoriteCount(safeLong(source.get("favorite_count")))
                .viewCount(safeLong(source.get("view_count")))
                .status(safeText(source.get("status")))
                .imgUrls(stringList(source.get("img_urls")))
                .isTop(safeBoolean(source.get("is_top")))
                .titleSuggest(safeText(source.get("title_suggest")))
                .build();
    }

    private ObjectNode toIndexBody(SearchDocumentVO doc) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("content_id", doc.getContentId());
        root.put("content_type", doc.getContentType() == null ? "POST" : doc.getContentType());
        putNullable(root, "title", doc.getTitle());
        putNullable(root, "description", doc.getDescription());
        putNullable(root, "body", doc.getBody());
        root.set("tags", stringArray(doc.getTags()));
        putNullable(root, "author_id", doc.getAuthorId());
        putNullable(root, "author_avatar", doc.getAuthorAvatar());
        putNullable(root, "author_nickname", doc.getAuthorNickname());
        putNullable(root, "author_tag_json", doc.getAuthorTagJson());
        putNullable(root, "publish_time", doc.getPublishTime());
        putNullable(root, "like_count", doc.getLikeCount());
        putNullable(root, "favorite_count", doc.getFavoriteCount());
        putNullable(root, "view_count", doc.getViewCount());
        putNullable(root, "status", doc.getStatus());
        root.set("img_urls", stringArray(doc.getImgUrls()));
        if (doc.getIsTop() == null) {
            root.putNull("is_top");
        } else {
            root.put("is_top", doc.getIsTop());
        }
        putNullable(root, "title_suggest", doc.getTitleSuggest());
        return root;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            if (value != null) {
                array.add(value);
            }
        }
        return array;
    }

    private void putNullable(ObjectNode root, String field, String value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

    private void putNullable(ObjectNode root, String field, Long value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

    private String firstHighlight(JsonNode hit, String field) {
        JsonNode arr = hit == null ? null : hit.path("highlight").path(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        return safeText(arr.get(0));
    }

    private String encodeAfter(JsonNode sortNode) {
        if (sortNode == null || !sortNode.isArray() || sortNode.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(sortNode.size());
        for (JsonNode node : sortNode) {
            parts.add(node == null || node.isNull() ? "" : node.asText());
        }
        String raw = String.join(",", parts);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ArrayNode decodeAfter(String after) {
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
            String[] parts = raw.split(",", -1);
            if (parts.length != 5) {
                return null;
            }
            ArrayNode array = objectMapper.createArrayNode();
            array.add(Double.parseDouble(parts[0]));
            array.add(Long.parseLong(parts[1]));
            array.add(Long.parseLong(parts[2]));
            array.add(Long.parseLong(parts[3]));
            array.add(Long.parseLong(parts[4]));
            return array;
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo(), e);
        }
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = safeText(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private String safeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private Long safeLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean safeBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asBoolean();
    }
}
