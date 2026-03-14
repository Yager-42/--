package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IPostSummaryPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.PostSummaryMqConfig;
import cn.nexus.types.event.PostSummaryGenerateEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 内容摘要生成消费者：回表 -> 调用摘要端口 -> 写回 summary/status。
 *
 * @author {$authorName}
 * @since 2026-03-01
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostSummaryGenerateConsumer {

    private static final String CONSUMER_NAME = "PostSummaryGenerateConsumer";
    private static final int SUMMARY_STATUS_DONE = 1;
    private static final int SUMMARY_STATUS_FAILED = 2;

    private final IContentRepository contentRepository;
    private final IPostSummaryPort postSummaryPort;
    private final ReliableMqConsumerRecordService consumerRecordService;
    private final ObjectMapper objectMapper;

    /**
     * 消费一条“摘要生成”事件，为帖子生成摘要并写回主表。
     *
     * <p>幂等策略：</p>
     * <p>1. consumerRecord 做事件级幂等，防止重复消费。</p>
     * <p>2. 若帖子已存在 DONE 的摘要，则直接跳过，避免重复调用模型。</p>
     *
     * @param event 摘要生成事件 {@link PostSummaryGenerateEvent}
     */
    @RabbitListener(queues = PostSummaryMqConfig.Q_POST_SUMMARY_GENERATE, containerFactory = "reliableMqListenerContainerFactory")
    public void onPostSummaryGenerate(PostSummaryGenerateEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("post.summary.generate missing postId");
        }
        // 1. 事件级幂等：同一 eventId 只允许一次成功副作用。
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        Long postId = event.getPostId();
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
            // 帖子不存在：视为无需处理，直接 DONE。
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
            log.info("event=content.summary.skip postId={} reason=POST_NOT_FOUND", postId);
            return;
        }

        Integer status = post.getSummaryStatus();
        String existed = post.getSummary();
        if (status != null && status == SUMMARY_STATUS_DONE && existed != null && !existed.isBlank()) {
            // 幂等：已经有摘要就直接跳过，避免重复调用模型。
            consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
            log.info("event=content.summary.skip postId={} reason=ALREADY_DONE", postId);
            return;
        }

        String summary;
        try {
            // 2. 调用摘要端口：当前是占位实现，后续可替换为真实模型调用。
            summary = postSummaryPort.summarize(post.getContentText(), post.getMediaInfo());
        } catch (Exception e) {
            // 失败时写回 FAILED，方便前台/后台看到状态并做补偿。
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.warn("event=content.summary.failed postId={} reason=PORT_EXCEPTION", postId, e);
            throw new AmqpRejectAndDontRequeueException("post summary port failed", e);
        }

        if (summary == null || summary.isBlank()) {
            // 空摘要视为失败：避免把“空字符串”当成已完成，导致永久不再补偿。
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, "EMPTY_SUMMARY");
            throw new AmqpRejectAndDontRequeueException("post summary empty");
        }

        // 3. 写回摘要与状态。
        boolean ok = contentRepository.updatePostSummary(postId, summary, SUMMARY_STATUS_DONE);
        consumerRecordService.markDone(event.getEventId(), CONSUMER_NAME);
        log.info("event=content.summary.done postId={} ok={}", postId, ok);
    }

    private String toJson(PostSummaryGenerateEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}

