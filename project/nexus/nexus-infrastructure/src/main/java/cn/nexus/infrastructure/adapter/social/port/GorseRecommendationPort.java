package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.config.FeedRecommendProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

/**
 * Gorse 推荐端口实现：通过 REST API 调用推荐系统。
 *
 * <p>注意：baseUrl 为空表示未启用 gorse；此时读接口返回空列表，写接口静默 no-op。</p>
 *
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

    @PostConstruct
    public void init() {
        int connectTimeoutMs = Math.max(1, feedRecommendProperties.getConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public List<Long> recommend(Long userId, int n) {
        if (userId == null || n <= 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        int normalizedN = Math.max(1, n);
        String url = baseUrl + "/api/recommend/" + urlEncode(String.valueOf(userId)) + "?n=" + normalizedN;
        try {
            String body = doRequest("GET", url, null);
            List<String> ids = objectMapper.readValue(body, new TypeReference<>() {
            });
            return parseIdList(ids);
        } catch (Exception e) {
            throw new RuntimeException("gorse recommend failed", e);
        }
    }

    @Override
    public List<Long> popular(Long userId, int n, int offset) {
        if (userId == null || n <= 0 || offset < 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        int normalizedN = Math.max(1, n);
        int normalizedOffset = Math.max(0, offset);
        String url = baseUrl + "/api/popular?user-id=" + userId + "&n=" + normalizedN + "&offset=" + normalizedOffset;
        try {
            String body = doRequest("GET", url, null);
            List<GorseIdScore> items = objectMapper.readValue(body, new TypeReference<>() {
            });
            List<String> ids = new ArrayList<>(items == null ? 0 : items.size());
            if (items != null) {
                for (GorseIdScore item : items) {
                    if (item == null || item.id == null || item.id.isBlank()) {
                        continue;
                    }
                    ids.add(item.id);
                }
            }
            return parseIdList(ids);
        } catch (Exception e) {
            throw new RuntimeException("gorse popular failed", e);
        }
    }

    @Override
    public List<Long> neighbors(Long postId, int n) {
        if (postId == null || n <= 0) {
            return List.of();
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return List.of();
        }
        int normalizedN = Math.max(1, n);
        String url = baseUrl + "/api/item/" + urlEncode(String.valueOf(postId)) + "/neighbors?n=" + normalizedN;
        try {
            String body = doRequest("GET", url, null);
            List<GorseIdScore> items = objectMapper.readValue(body, new TypeReference<>() {
            });
            List<String> ids = new ArrayList<>(items == null ? 0 : items.size());
            if (items != null) {
                for (GorseIdScore item : items) {
                    if (item == null || item.id == null || item.id.isBlank()) {
                        continue;
                    }
                    ids.add(item.id);
                }
            }
            return parseIdList(ids);
        } catch (Exception e) {
            throw new RuntimeException("gorse neighbors failed", e);
        }
    }

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

    @Override
    public void insertFeedback(Long userId, Long postId, String feedbackType, Long timestampMs) {
        if (userId == null || postId == null) {
            return;
        }
        if (feedbackType == null || feedbackType.isBlank()) {
            return;
        }
        String baseUrl = baseUrl();
        if (baseUrl.isEmpty()) {
            return;
        }
        long safeTs = timestampMs == null ? System.currentTimeMillis() : timestampMs;
        String url = baseUrl + "/api/feedback";
        try {
            List<GorseFeedbackBody> req = List.of(new GorseFeedbackBody(
                    feedbackType.trim(),
                    String.valueOf(postId),
                    formatTsMs(safeTs),
                    String.valueOf(userId)
            ));
            String body = objectMapper.writeValueAsString(req);
            doRequest("POST", url, body);
        } catch (Exception e) {
            throw new RuntimeException("gorse insertFeedback failed", e);
        }
    }

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
            if (parsed == null) {
                continue;
            }
            result.add(parsed);
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
            // 理论上不会发生：作为兜底，避免 NPE。
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
