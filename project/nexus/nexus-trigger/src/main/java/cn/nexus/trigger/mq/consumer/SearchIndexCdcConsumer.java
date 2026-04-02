package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexCdcConsumer {

    private static final String CONSUMER_NAME = "search-index-cdc";

    private final SearchIndexUpsertService searchIndexUpsertService;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = SearchIndexCdcMqConfig.QUEUE, containerFactory = "searchIndexListenerContainerFactory")
    public void onPostChanged(PostChangedCdcEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("post.changed.cdc missing required fields");
        }
        String payloadJson = safeJson(event);
        boolean allowed = consumerRecordService.start(event.getEventId(), CONSUMER_NAME, payloadJson);
        if (!allowed) {
            return;
        }

        long startNs = System.nanoTime();
        try {
            SearchIndexUpsertService.SearchIndexAction action = searchIndexUpsertService.upsertPost(event.getPostId());
            if (action.softDeleted()) {
                log.info("event=search.index.cdc.soft_delete eventId={} postId={} reason={} costMs={}",
                        event.getEventId(), event.getPostId(), action.reason(), costMs(startNs));
            } else {
                log.info("event=search.index.cdc.upsert eventId={} postId={} costMs={}",
                        event.getEventId(), event.getPostId(), costMs(startNs));
            }
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        } catch (Exception e) {
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getClass().getSimpleName());
            throw e;
        }
    }

    private String safeJson(PostChangedCdcEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return null;
        }
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
