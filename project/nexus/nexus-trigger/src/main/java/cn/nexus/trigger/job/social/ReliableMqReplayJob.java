package cn.nexus.trigger.job.social;

import cn.nexus.infrastructure.mq.reliable.ReliableMqReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通用 RabbitMQ replay 任务：统一接管 DLQ 失败记录。
 */
@Component
@RequiredArgsConstructor
public class ReliableMqReplayJob {

    private final ReliableMqReplayService reliableMqReplayService;

    @Scheduled(fixedDelay = 60000)
    public void replayReady() {
        reliableMqReplayService.replayReady(200);
    }
}
