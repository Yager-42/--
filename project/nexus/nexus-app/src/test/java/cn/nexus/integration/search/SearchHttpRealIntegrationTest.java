package cn.nexus.integration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.Date;
import org.elasticsearch.client.Request;
import org.junit.jupiter.api.Test;

class SearchHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void searchAndSuggest_shouldQueryElasticsearchBackedIndex() throws Exception {
        TestSession author = registerAndLoginSession("search-author");
        TestSession searcher = registerAndLoginSession("searcher");

        long postId = uniqueId();
        long nowMs = System.currentTimeMillis();
        String keyword = "kw" + uniqueUuid().substring(0, 6);
        String title = "sug-" + keyword + "-it-title-" + uniqueUuid().substring(0, 6);

        seedPublishedPost(author.userId(), postId, title, nowMs);

        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        publishSearchIndexCdc(postId, nowMs);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("content_id").asLong()).isEqualTo(postId);
            assertThat(source.path("title").asText()).isEqualTo(title);
            assertThat(source.path("status").asText()).isEqualTo("published");
        });

        // GET /_doc 是 realtime，但搜索结果需要 refresh 才能稳定可见。
        searchRestClient.performRequest(new Request("POST", "/" + indexAlias + "/_refresh"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode search = assertSuccess(getJson("/api/v1/search?q=" + title + "&size=50", searcher.token()));
            assertThat(search.path("items"))
                    .extracting(JsonNode::toString)
                    .anySatisfy(raw -> assertThat(raw).contains("\"id\":\"" + postId + "\""));
        });

        String prefix = title.substring(0, Math.min(12, title.length()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode suggest = assertSuccess(getJson("/api/v1/search/suggest?prefix=" + prefix + "&size=10", searcher.token()));
            assertThat(suggest.path("items"))
                    .extracting(JsonNode::asText)
                    .contains(title);
        });
    }

    @Test
    void search_highConcurrencySmoke_shouldRemainAvailable() throws Exception {
        TestSession author = registerAndLoginSession("search-load-author");
        TestSession searcher = registerAndLoginSession("search-load-user");

        long postId = uniqueId();
        long nowMs = System.currentTimeMillis();
        String keyword = "kw" + uniqueUuid().substring(0, 6);
        String title = "it-title-" + keyword + "-" + uniqueUuid().substring(0, 6);
        seedPublishedPost(author.userId(), postId, title, nowMs);

        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        publishSearchIndexCdc(postId, nowMs);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(fetchDocumentSource(postId)).isNotNull());
        searchRestClient.performRequest(new Request("POST", "/" + indexAlias + "/_refresh"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode search = assertSuccess(getJson("/api/v1/search?q=" + keyword + "&size=10", searcher.token()));
            assertThat(search.path("items"))
                    .extracting(JsonNode::toString)
                    .anySatisfy(raw -> assertThat(raw).contains("\"id\":\"" + postId + "\""));
        });

        ConcurrentRunResult result = runConcurrentRequests(100, 20, 60,
                () -> assertSearchContainsPost(keyword, postId, searcher.token(), 3, 30L));

        printLoadSmoke("search-query", result);
        assertThat(result.failure()).isEqualTo(0);
        assertThat(result.success()).isEqualTo(result.totalRequests());
    }

    @Test
    void nicknameRefreshAndSoftDelete_shouldPropagateToSearchIndex() throws Exception {
        TestSession author = registerAndLoginSession("search-index-author");
        registerAndLoginSession("search-index-user");

        long postId = uniqueId();
        long nowMs = System.currentTimeMillis();
        String keyword = "kw" + uniqueUuid().substring(0, 6);
        String title = "it-title-" + keyword + "-" + uniqueUuid().substring(0, 6);
        seedPublishedPost(author.userId(), postId, title, nowMs);

        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        publishSearchIndexCdc(postId, nowMs);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(fetchDocumentSource(postId)).isNotNull());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("author_id").asLong()).isEqualTo(author.userId());
        });
        String newNickname = "search-new-" + uniqueUuid().substring(0, 6);
        assertSuccess(postJson("/api/v1/user/me/profile", JsonNodeFactory.instance.objectNode()
                .put("nickname", newNickname), author.token()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(assertSuccess(getJson("/api/v1/auth/me", author.token())).path("nickname").asText())
                        .isEqualTo(newNickname));
        deleteRedisKey("social:userbase:" + author.userId());
        publishPendingUserEvents();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("author_nickname").asText()).isEqualTo(newNickname);
        });

        contentPostDao.updateStatus(postId, 6);
        publishSearchIndexCdc(postId, System.currentTimeMillis());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("status").asText()).isEqualTo("deleted");
        });
    }

    @Test
    void postLikeSnapshot_shouldRefreshIndexedLikeCount() throws Exception {
        TestSession author = registerAndLoginSession("search-like-author");
        TestSession liker = registerAndLoginSession("search-like-user");

        long postId = uniqueId();
        long nowMs = System.currentTimeMillis();
        String title = "search-like-" + uniqueUuid().substring(0, 8);
        seedPublishedPost(author.userId(), postId, title, nowMs);

        deleteRedisKey("interact:content:author:" + postId);
        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        publishSearchIndexCdc(postId, nowMs);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("like_count").asLong()).isEqualTo(0L);
        });

        JsonNode like = assertSuccess(postJson("/api/v1/interact/reaction", JsonNodeFactory.instance.objectNode()
                .put("requestId", "rid-" + uniqueUuid())
                .put("targetId", postId)
                .put("targetType", "POST")
                .put("type", "LIKE")
                .put("action", "ADD"), liker.token()));
        assertThat(like.path("currentCount").asLong()).isEqualTo(1L);

        publishPendingReliableMqMessages();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("like_count").asLong()).isEqualTo(1L);
        });
    }

    private void seedPublishedPost(long authorId, long postId, String title, long nowMs) {
        Date now = new Date(nowMs);
        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(authorId);
        post.setTitle(title);
        post.setContentUuid(uniqueUuid());
        post.setSummary("search integration");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setCreateTime(now);
        post.setPublishTime(now);
        contentPostDao.insert(post);
        contentPostTypeDao.insertBatch(postId, java.util.List.of("integration", "search"));
        postContentKvPort.add(post.getContentUuid(), "这是一段会进入 Elasticsearch 的真实正文 " + uniqueUuid());
    }

    private TestSession registerAndLoginSession(String nicknamePrefix) throws Exception {
        String phone = uniquePhone();
        String password = "Pwd@" + uniqueUuid().substring(0, 8);
        String nickname = nicknamePrefix + "-" + uniqueUuid().substring(0, 6);
        String token = registerAndLogin(phone, password, nickname);
        long userId = assertSuccess(getJson("/api/v1/auth/me", token)).path("userId").asLong();
        return new TestSession(userId, token);
    }

    private void assertSearchContainsPost(String keyword, long postId, String token, int maxAttempts, long backoffMs)
            throws Exception {
        AssertionError last = null;
        for (int i = 0; i < Math.max(1, maxAttempts); i++) {
            try {
                JsonNode search = assertSuccess(getJson("/api/v1/search?q=" + keyword + "&size=10", token));
                assertThat(search.path("items"))
                        .extracting(JsonNode::toString)
                        .anySatisfy(raw -> assertThat(raw).contains("\"id\":\"" + postId + "\""));
                return;
            } catch (AssertionError e) {
                last = e;
                if (i + 1 < maxAttempts && backoffMs > 0) {
                    Thread.sleep(backoffMs);
                }
            }
        }
        if (last == null) {
            throw new AssertionError("search result did not contain postId=" + postId);
        }
        throw last;
    }

    private void publishSearchIndexCdc(long postId, long tsMs) {
        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setEventId("it:" + postId + ":" + tsMs);
        event.setPostId(postId);
        event.setTsMs(tsMs);
        event.setSource("it");
        event.setTable("content_post");
        rabbitTemplate.convertAndSend(SearchIndexCdcMqConfig.EXCHANGE, SearchIndexCdcMqConfig.ROUTING_KEY, event);
    }

    private record TestSession(long userId, String token) {
    }
}
