package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import cn.nexus.types.enums.ContentMediaTypeEnumVO;
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

/**
 * Search 索引更新消费者：回表 -> upsert/delete（幂等 docId）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexConsumer {

    private final ISearchEnginePort searchEnginePort;
    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_PUBLISHED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostPublished(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null || event.getPublishTimeMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.published missing required fields");
        }
        handleUpsert(event.getPostId(), event.getPublishTimeMs());
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_UPDATED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostUpdated(PostUpdatedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.updated missing required fields");
        }
        handleUpsert(event.getPostId(), event.getTsMs());
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_POST_DELETED, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostDeleted(PostDeletedEvent event) {
        if (event == null || event.getPostId() == null || event.getOperatorId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("post.deleted missing required fields");
        }
        handleDelete(event.getPostId(), "EVENT_DELETED");
    }

    @RabbitListener(queues = SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED, containerFactory = "searchIndexListenerContainerFactory")
    public void onUserNicknameChanged(UserNicknameChangedEvent event) {
        if (event == null || event.getUserId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("user.nickname_changed missing required fields");
        }
        long startNs = System.nanoTime();
        Long userId = event.getUserId();
        String nickname = resolveNickname(userId);
        long affected = searchEnginePort.updateAuthorNickname(userId, nickname);
        long costMs = costMs(startNs);
        log.info("event=search.index.nickname_update userId={} affected={} costMs={}", userId, affected, costMs);
    }

    private void handleUpsert(Long postId, Long eventTimeMs) {
        long startNs = System.nanoTime();
        String docId = docId(postId);

        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
            searchEnginePort.delete(docId);
            log.info("event=search.index.delete docId={} postId={} reason=POST_NOT_FOUND costMs={}", docId, postId, costMs(startNs));
            return;
        }

        Integer status = post.getStatus();
        if (status == null || status != ContentPostStatusEnumVO.PUBLISHED.getCode()) {
            searchEnginePort.delete(docId);
            log.info("event=search.index.delete docId={} postId={} reason=NOT_PUBLISHED costMs={}", docId, postId, costMs(startNs));
            return;
        }

        Integer visibility = post.getVisibility();
        if (visibility == null || visibility != ContentPostVisibilityEnumVO.PUBLIC.getCode()) {
            searchEnginePort.delete(docId);
            log.info("event=search.index.delete docId={} postId={} reason=NOT_PUBLIC costMs={}", docId, postId, costMs(startNs));
            return;
        }

        Long createTimeMs = post.getCreateTime();
        if (createTimeMs == null) {
            createTimeMs = eventTimeMs == null ? System.currentTimeMillis() : eventTimeMs;
        }

        Integer mediaType = post.getMediaType() == null ? ContentMediaTypeEnumVO.TEXT.getCode() : post.getMediaType();
        String nickname = resolveNickname(post.getUserId());

        SearchDocumentVO doc = SearchDocumentVO.builder()
                .entityIdStr(String.valueOf(postId))
                .createTimeMs(createTimeMs)
                .postId(postId)
                .authorId(post.getUserId())
                .authorNickname(nickname)
                .contentText(post.getContentText() == null ? "" : post.getContentText())
                .postTypes(normalizePostTypes(post.getPostTypes()))
                .mediaType(mediaType)
                .build();

        searchEnginePort.upsert(doc);
        log.info("event=search.index.upsert docId={} postId={} costMs={}", docId, postId, costMs(startNs));
    }

    private void handleDelete(Long postId, String reason) {
        long startNs = System.nanoTime();
        String docId = docId(postId);
        searchEnginePort.delete(docId);
        log.info("event=search.index.delete docId={} postId={} reason={} costMs={}", docId, postId, reason, costMs(startNs));
    }

    private String resolveNickname(Long userId) {
        if (userId == null) {
            return "";
        }
        List<UserBriefVO> list = userBaseRepository.listByUserIds(List.of(userId));
        if (list == null || list.isEmpty()) {
            return "";
        }
        String nick = list.get(0) == null ? null : list.get(0).getNickname();
        return nick == null ? "" : nick;
    }

    private List<String> normalizePostTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> res = new java.util.ArrayList<>(Math.min(5, raw.size()));
        for (String s : raw) {
            if (s == null) {
                continue;
            }
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (res.contains(v)) {
                continue;
            }
            res.add(v);
            if (res.size() >= 5) {
                break;
            }
        }
        return res;
    }

    private String docId(Long postId) {
        return "POST:" + postId;
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}

