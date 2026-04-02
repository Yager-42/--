package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.SearchIndexMqConfig;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.UserNicknameChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexConsumer {

    private final SearchIndexUpsertService searchIndexUpsertService;

    @RabbitListener(queues = SearchIndexMqConfig.Q_USER_NICKNAME_CHANGED, containerFactory = "searchIndexListenerContainerFactory")
    public void onUserNicknameChanged(UserNicknameChangedEvent event) {
        if (event == null || event.getUserId() == null || event.getTsMs() == null) {
            throw new AmqpRejectAndDontRequeueException("user.nickname_changed missing required fields");
        }
        long startNs = System.nanoTime();
        long affected = searchIndexUpsertService.updateAuthorNickname(event.getUserId());
        log.info("event=search.index.nickname_update userId={} affected={} costMs={}",
                event.getUserId(), affected, costMs(startNs));
    }

    private long costMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
