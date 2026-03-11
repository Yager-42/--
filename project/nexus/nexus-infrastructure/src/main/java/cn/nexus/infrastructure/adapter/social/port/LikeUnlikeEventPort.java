package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Like/Unlike MQ publisher (RabbitMQ).
 */
@Component
@RequiredArgsConstructor
public class LikeUnlikeEventPort implements ILikeUnlikeEventPort {

    private static final String EXCHANGE = "LikeUnlikeTopic";
    private static final String RK_LIKE = "Like";
    private static final String RK_UNLIKE = "Unlike";

    private final ReliableMqOutboxService reliableMqOutboxService;

    @Override
    public void publishLike(LikeUnlikePostEvent event) {
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_LIKE, event);
    }

    @Override
    public void publishUnlike(LikeUnlikePostEvent event) {
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_UNLIKE, event);
    }
}
