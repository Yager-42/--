package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.adapter.social.repository.ContentRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardStatRepository;
import cn.nexus.trigger.cache.ContentCacheEvictEvent;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentCacheEvictConsumer {

    private final ContentRepository contentRepository;
    private final ContentDetailQueryService contentDetailQueryService;
    private final FeedCardRepository feedCardRepository;
    private final FeedCardStatRepository feedCardStatRepository;

    @RabbitListener(queues = "#{contentCacheEvictQueue.name}")
    public void onMessage(ContentCacheEvictEvent event) {
        if (event == null || event.getPostId() == null) {
            return;
        }
        Long postId = event.getPostId();
        try {
            contentRepository.evictLocalPostCache(postId);
        } catch (Exception e) {
            log.warn("evict local post cache failed, postId={}", postId, e);
        }
        try {
            contentDetailQueryService.evictLocal(postId);
        } catch (Exception e) {
            log.warn("evict local detail cache failed, postId={}", postId, e);
        }
        try {
            feedCardRepository.evictLocal(postId);
        } catch (Exception e) {
            log.warn("evict local feed card cache failed, postId={}", postId, e);
        }
        try {
            feedCardStatRepository.evictLocal(postId);
        } catch (Exception e) {
            log.warn("evict local feed card stat cache failed, postId={}", postId, e);
        }
    }
}
