package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Like/Unlike MQ publisher (RabbitMQ).
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-03
 */
@Component
@RequiredArgsConstructor
public class LikeUnlikeEventPort implements ILikeUnlikeEventPort {

    private static final String EXCHANGE = "LikeUnlikeTopic";
    private static final String RK_LIKE = "Like";
    private static final String RK_UNLIKE = "Unlike";

    private final ReliableMqOutboxService reliableMqOutboxService;

    /**
     * 执行 publishLike 逻辑。
     *
     * @param event 事件对象。类型：{@link LikeUnlikePostEvent}
     */
    @Override
    public void publishLike(LikeUnlikePostEvent event) {
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_LIKE, event);
    }

    /**
     * 执行 publishUnlike 逻辑。
     *
     * @param event 事件对象。类型：{@link LikeUnlikePostEvent}
     */
    @Override
    public void publishUnlike(LikeUnlikePostEvent event) {
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_UNLIKE, event);
    }
}
