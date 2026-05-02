package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexCdcConsumer {

    private static final String CONSUMER_NAME = "search-index-cdc";

    private final SearchIndexUpsertService searchIndexUpsertService;

    @ReliableMqConsume(consumerName = CONSUMER_NAME, eventId = "#event.eventId", payload = "#event")
    @RabbitListener(queues = SearchIndexCdcMqConfig.QUEUE, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostChanged(PostChangedCdcEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("post.changed.cdc missing required fields");
        }

        long startNs = System.nanoTime();
        SearchIndexUpsertService.SearchIndexAction action = searchIndexUpsertService.upsertPost(event.getPostId());
        if (action.softDeleted()) {
            log.info("event=search.index.cdc.soft_delete eventId={} postId={} reason={} costMs={}",
                    event.getEventId(), event.getPostId(), action.reason(), costMs(startNs));
        } else {
            log.info("event=search.index.cdc.upsert eventId={} postId={} costMs={}",
                    event.getEventId(), event.getPostId(), costMs(startNs));
        }
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
