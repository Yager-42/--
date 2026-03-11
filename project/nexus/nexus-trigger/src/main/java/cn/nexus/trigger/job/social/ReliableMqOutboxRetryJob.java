package cn.nexus.trigger.job.social;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用 RabbitMQ Outbox 定时发布任务。
 */
@Component
@RequiredArgsConstructor
public class ReliableMqOutboxRetryJob {

    private final ReliableMqOutboxService reliableMqOutboxService;

    @Scheduled(fixedDelay = 60000)
    public void publishReady() {
        reliableMqOutboxService.publishReady(200);
    }
}
