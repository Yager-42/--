package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import cn.nexus.types.event.PostPublishedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Date;
import org.junit.jupiter.api.Test;

class SearchIndexConsumerRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void postPublishedEvent_shouldFlowThroughRabbitMqAndIndexIntoElasticsearch() {
        long userId = uniqueId();
        long postId = uniqueId();
        long publishTimeMs = System.currentTimeMillis();
        String contentUuid = uniqueUuid();

        UserBasePO user = new UserBasePO();
        user.setUserId(userId);
        user.setUsername("search_author_" + userId);
        user.setNickname("检索作者-" + userId);
        user.setAvatarUrl("https://avatar.example/search-" + userId + ".png");
        userBaseDao.insert(user);

        ContentPostPO post = new ContentPostPO();
        post.setPostId(postId);
        post.setUserId(userId);
        post.setTitle("真实链路检索测试-" + postId);
        post.setContentUuid(contentUuid);
        post.setSummary("真实中间件链路测试摘要");
        post.setSummaryStatus(1);
        post.setMediaType(0);
        post.setMediaInfo(null);
        post.setLocationInfo(null);
        post.setStatus(ContentPostStatusEnumVO.PUBLISHED.getCode());
        post.setVisibility(ContentPostVisibilityEnumVO.PUBLIC.getCode());
        post.setVersionNum(1);
        post.setIsEdited(0);
        post.setPublishTime(new Date(publishTimeMs));
        contentPostDao.insert(post);
        contentPostTypeDao.insertBatch(postId, java.util.List.of("integration", "search"));
        postContentKvPort.add(contentUuid, "这是一段会进入 Elasticsearch 的真实正文");
        reactionRepository.upsertCount(ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build(), 7L);

        deleteRedisKey("social:userbase:" + userId);
        deleteRedisKey("interact:content:post:" + postId);
        deleteDocumentQuietly(postId);

        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(postId);
        event.setAuthorId(userId);
        event.setPublishTimeMs(publishTimeMs);
        rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, SearchIndexMqConfig.RK_POST_PUBLISHED, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode source = fetchDocumentSource(postId);
            assertThat(source).isNotNull();
            assertThat(source.path("content_id").asLong()).isEqualTo(postId);
            assertThat(source.path("title").asText()).isEqualTo("真实链路检索测试-" + postId);
            assertThat(source.path("body").asText()).isEqualTo("这是一段会进入 Elasticsearch 的真实正文");
            assertThat(source.path("author_id").asLong()).isEqualTo(userId);
            assertThat(source.path("author_nickname").asText()).isEqualTo("检索作者-" + userId);
            assertThat(source.path("like_count").asLong()).isEqualTo(7L);
            assertThat(source.path("status").asText()).isEqualTo("published");
            assertThat(tagsOf(source)).contains("integration", "search");
        });
    }
}
