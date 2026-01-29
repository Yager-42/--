package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;

/**
 * 风控异步任务端口：把“需要异步处理”的工作投递到 MQ。
 *
 * <p>领域层只关心“我要投递什么任务”，不关心 MQ 的拓扑与实现细节。</p>
 */
public interface IRiskTaskPort {

    void dispatchLlmScan(LlmScanRequestedEvent event);

    void dispatchImageScan(ImageScanRequestedEvent event);

    void dispatchReviewCase(ReviewCaseCreatedEvent event);
}

