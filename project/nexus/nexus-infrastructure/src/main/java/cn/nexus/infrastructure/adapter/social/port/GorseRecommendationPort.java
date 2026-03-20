package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.config.FeedRecommendProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gorse 推荐端口实现：通过 REST API 调用推荐系统。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GorseRecommendationPort implements IRecommendationPort {

    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final FeedRecommendProperties feedRecommendProperties;
    private final ObjectMapper objectMapper;

    private volatile HttpClient httpClient;

    /**
     * 执行 init 逻辑。
     *
     */
    @PostConstruct
    public void init() {
        int connectTimeoutMs = Math.max(1, feedRecommendProperties.getConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    /**
     * 执行 recommend 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param n n 参数。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> recommend(Long userId, int n) {
        if (userId == null || n <= 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/recommend/" + urlEncode(String.valueOf(userId)) + "?n=" + Math.max(1, n);
        try {
            String body = doRequest("GET", url, null);
            List<String> ids = objectMapper.readValue(body, new TypeReference<>() {});
            return parseIdList(ids);
        } catch (Exception e) {
            throw new RuntimeException("gorse recommend failed", e);
        }
    }

    /**
     * 执行 nonPersonalized 逻辑。
     *
     * @param name name 参数。类型：{@link String}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param n n 参数。类型：{@code int}
     * @param offset offset 参数。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> nonPersonalized(String name, Long userId, int n, int offset) {
        if (name == null || name.isBlank() || n <= 0 || offset < 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/non-personalized/" + urlEncode(name.trim())
                + "?n=" + Math.max(1, n)
                + "&offset=" + Math.max(0, offset)
                + (userId == null ? "" : "&user-id=" + userId);
        try {
            String body = doRequest("GET", url, null);
            List<GorseIdScore> items = objectMapper.readValue(body, new TypeReference<>() {});
            return parseScoredIdList(items);
        } catch (Exception e) {
            throw new RuntimeException("gorse non-personalized failed", e);
        }
    }

    /**
     * 执行 sessionRecommend 逻辑。
     *
     * @param sessionId sessionId 参数。类型：{@link String}
     * @param currentItemIds currentItemIds 参数。类型：{@link List}
     * @param n n 参数。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> sessionRecommend(String sessionId, List<Long> currentItemIds, int n) {
        if (sessionId == null || sessionId.isBlank() || n <= 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/session/recommend?n=" + Math.max(1, n);
        try {
            List<GorseFeedbackBody> req = new ArrayList<>();
            long nowMs = System.currentTimeMillis();
            if (currentItemIds != null) {
                for (Long itemId : currentItemIds) {
                    if (itemId == null) {
                        continue;
                    }
                    req.add(new GorseFeedbackBody("read", String.valueOf(itemId), formatTsMs(nowMs), sessionId));
                }
            }
            String body = objectMapper.writeValueAsString(req);
            String resp = doRequest("POST", url, body);
            List<GorseIdScore> items = objectMapper.readValue(resp, new TypeReference<>() {});
            return parseScoredIdList(items);
        } catch (Exception e) {
            throw new RuntimeException("gorse session recommend failed", e);
        }
    }

    /**
     * 执行 itemToItem 逻辑。
     *
     * @param name name 参数。类型：{@link String}
     * @param itemId itemId 参数。类型：{@link Long}
     * @param n n 参数。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<Long> itemToItem(String name, Long itemId, int n) {
        if (name == null || name.isBlank() || itemId == null || n <= 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/item-to-item/" + urlEncode(name.trim()) + "/" + urlEncode(String.valueOf(itemId))
                + "?n=" + Math.max(1, n);
        try {
            String body = doRequest("GET", url, null);
            List<GorseIdScore> items = objectMapper.readValue(body, new TypeReference<>() {});
            return parseScoredIdList(items);
        } catch (Exception e) {
            throw new RuntimeException("gorse item-to-item failed", e);
        }
    }

    /**
     * 执行 upsertItem 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     * @param labels labels 参数。类型：{@link List}
     * @param timestampMs timestampMs 参数。类型：{@link Long}
     */
    @Override
    public void upsertItem(Long postId, List<String> labels, Long timestampMs) {
        if (postId == null) {
            return;
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return;
        }
        List<String> safeLabels = labels == null ? List.of() : labels;
        long safeTs = timestampMs == null ? System.currentTimeMillis() : timestampMs;
        String url = baseUrl + "/api/item";
        try {
            String body = objectMapper.writeValueAsString(new GorseItemBody(String.valueOf(postId), safeLabels, formatTsMs(safeTs)));
            doRequest("POST", url, body);
        } catch (Exception e) {
            throw new RuntimeException("gorse upsertItem failed", e);
        }
    }

    /**
     * 执行 insertFeedback 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     * @param feedbackType feedbackType 参数。类型：{@link String}
     * @param timestampMs timestampMs 参数。类型：{@link Long}
     */
    @Override
    public void insertFeedback(Long userId, Long postId, String feedbackType, Long timestampMs) {
        if (userId == null || postId == null || feedbackType == null || feedbackType.isBlank()) {
            return;
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return;
        }
        long safeTs = timestampMs == null ? System.currentTimeMillis() : timestampMs;
        String url = baseUrl + "/api/feedback";
        try {
            List<GorseFeedbackBody> req = List.of(new GorseFeedbackBody(feedbackType.trim(), String.valueOf(postId), formatTsMs(safeTs), String.valueOf(userId)));
            String body = objectMapper.writeValueAsString(req);
            doRequest("POST", url, body);
        } catch (Exception e) {
            throw new RuntimeException("gorse insertFeedback failed", e);
        }
    }

    /**
     * 执行 deleteItem 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     */
    @Override
    public void deleteItem(Long postId) {
        if (postId == null) {
            return;
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return;
        }
        String url = baseUrl + "/api/item/" + urlEncode(String.valueOf(postId));
        try {
            doRequest("DELETE", url, null);
        } catch (Exception e) {
            throw new RuntimeException("gorse deleteItem failed", e);
        }
    }

    private String baseUrl() {
        String raw = feedRecommendProperties.getBaseUrl();
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private String formatTsMs(long tsMs) {
        return TS_FORMATTER.format(Instant.ofEpochMilli(tsMs));
    }

    private List<Long> parseIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Long parsed = parseLong(id);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private List<Long> parseScoredIdList(List<GorseIdScore> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(items.size());
        for (GorseIdScore item : items) {
            if (item == null || item.id == null || item.id.isBlank()) {
                continue;
            }
            Long parsed = parseLong(item.id);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
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

    private String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private String doRequest(String method, String url, String body) throws Exception {
        HttpClient client = httpClient;
        if (client == null) {
            init();
            client = httpClient;
        }
        int readTimeoutMs = Math.max(1, feedRecommendProperties.getReadTimeoutMs());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/json");

        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else if ("DELETE".equalsIgnoreCase(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }

        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            String respBody = resp.body();
            throw new IllegalStateException("gorse http failed, status=" + status + ", body=" + safeBody(respBody));
        }
        return resp.body() == null ? "" : resp.body();
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        String v = body.replace("\n", " ").replace("\r", " ");
        if (v.length() <= 200) {
            return v;
        }
        return v.substring(0, 200) + "...";
    }

    private record GorseItemBody(
            @JsonProperty("ItemId") String itemId,
            @JsonProperty("Labels") List<String> labels,
            @JsonProperty("Timestamp") String timestamp
    ) {
    }

    private record GorseFeedbackBody(
            @JsonProperty("FeedbackType") String feedbackType,
            @JsonProperty("ItemId") String itemId,
            @JsonProperty("Timestamp") String timestamp,
            @JsonProperty("UserId") String userId
    ) {
    }

    private static class GorseIdScore {
        @JsonProperty("Id")
        private String id;

        @JsonProperty("Score")
        @SuppressWarnings("unused")
        private Double score;
    }
}
