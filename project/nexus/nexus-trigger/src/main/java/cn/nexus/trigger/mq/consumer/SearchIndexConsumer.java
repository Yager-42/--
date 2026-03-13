package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import cn.nexus.trigger.search.support.SearchDocumentAssembler;

/**
 * Search 索引事件消费者。
 *
 * <p>职责很单一：从 `MQ` 取事件，回主库拿真值，再把文档写入或删出搜索索引。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexConsumer {

    private final ISearchEnginePort searchEnginePort;
    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final IReactionRepository reactionRepository;
    private final SearchDocumentAssembler searchDocumentAssembler;

    /**
     * 处理帖子发布事件。
     *
     * @param event 帖子发布事件，类型：{@link PostPublishedEvent}
     */
    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_PUBLISHED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostPublished(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null || event.getPublishTimeMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.published missing required fields");
        }
        handleUpsert(event.getPostId());
    }

    /**
     * 处理帖子更新事件。
     *
     * @param event 帖子更新事件，类型：{@link PostUpdatedEvent}
     */
    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_UPDATED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostUpdated(PostUpdatedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.updated missing required fields");
        }
        handleUpsert(event.getPostId());
    }

    /**
     * 处理帖子删除事件。
     *
     * @param event 帖子删除事件，类型：{@link PostDeletedEvent}
     */
    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_DELETED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostDeleted(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.deleted missing required fields");
        }
        handleSoftDelete(event.getPostId(), "EVENT_DELETED");
    }

    /**
     * 处理用户昵称变更事件。
     *
     * @param event 用户昵称变更事件，类型：{@link UserNicknameChangedEvent}
     */
    @RabbitListener(queues = SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED, containerFactory = "searchIndexListenerContainerFactory")
    public void onUserNicknameChanged(UserNicknameChangedEvent event) {
        if (event == null || event.getUserId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("user.nickname_changed missing required fields");
        }
        long startNs = System.nanoTime();
        Long userId = event.getUserId();
        // 昵称永远回 `user_base` 取最新值，避免消息体携带旧副本。
        UserBriefVO author = resolveAuthor(userId);
        String nickname = author == null ? "" : safe(author.getNickname());
        long affected = searchEnginePort.updateAuthorNickname(userId, nickname);
        log.info("event=search.index.nickname_update userId={} affected={} costMs={}", userId, affected, costMs(startNs));
    }

    private void handleUpsert(Long postId) {
        long startNs = System.nanoTime();
        // 每次都先回内容主库拿真值；只要帖子不再可索引，就主动让索引侧收敛为删除状态。
        ContentPostEntity post = contentRepository.findPost(postId);
        if (!indexable(post)) {
            handleSoftDelete(postId, post == null ? "POST_NOT_FOUND" : "NOT_INDEXABLE");
            return;
        }
        // 文档组装阶段顺手补作者昵称和点赞数，保证搜索展示字段和读侧一致。
        SearchDocumentVO doc = buildDocument(post);
        if (doc == null) {
            handleSoftDelete(postId, "DOCUMENT_INVALID");
            return;
        }
        searchEnginePort.upsert(doc);
        log.info("event=search.index.upsert contentId={} costMs={}", postId, costMs(startNs));
    }

    private void handleSoftDelete(Long postId, String reason) {
        long startNs = System.nanoTime();
        if (postId == null) {
            return;
        }
        searchEnginePort.softDelete(postId);
        log.info("event=search.index.soft_delete contentId={} reason={} costMs={}", postId, reason, costMs(startNs));
    }

    private boolean indexable(ContentPostEntity post) {
        if (post == null || post.getPostId() == null) {
            return false;
        }
        if (post.getStatus() == null || post.getStatus() != ContentPostStatusEnumVO.PUBLISHED.getCode()) {
            return false;
        }
        if (post.getVisibility() == null || post.getVisibility() != ContentPostVisibilityEnumVO.PUBLIC.getCode()) {
            return false;
        }
        if (post.getTitle() == null || post.getTitle().isBlank()) {
            return false;
        }
        return post.getPublishTime() != null;
    }

    private SearchDocumentVO buildDocument(ContentPostEntity post) {
        if (post == null || post.getPostId() == null || post.getTitle() == null || post.getPublishTime() == null) {
            return null;
        }
        UserBriefVO author = resolveAuthor(post.getUserId());
        // 点赞数允许最终一致，但这里至少要保证写入索引时拿到一个非负值。
        long likeCount = reactionRepository.getCount(ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(post.getPostId())
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build());
        return searchDocumentAssembler.assemble(
                post.getPostId(),
                post.getUserId(),
                post.getTitle(),
                post.getSummary(),
                post.getContentText(),
                post.getPostTypes() == null ? List.of() : post.getPostTypes(),
                author == null ? null : author.getAvatarUrl(),
                author == null ? null : author.getNickname(),
                post.getPublishTime(),
                Math.max(0L, likeCount),
                post.getMediaInfo());
    }

    private UserBriefVO resolveAuthor(Long userId) {
        if (userId == null) {
            return null;
        }
        List<UserBriefVO> list = userBaseRepository.listByUserIds(List.of(userId));
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
