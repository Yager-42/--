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

    @RabbitListener(queues = PostSummaryMqConfig.Q_POST_SUMMARY_GENERATE, containerFactory = "reliableMqListenerContainerFactory")
    public void onPostSummaryGenerate(PostSummaryGenerateEvent event) {
        if (event == null || event.getPostId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("post.summary.generate missing postId");
        }
        if (!consumerRecordService.start(event.getEventId(), CONSUMER_NAME, toJson(event))) {
            return;
        }
        Long postId = event.getPostId();
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
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
            summary = postSummaryPort.summarize(post.getContentText(), post.getMediaInfo());
        } catch (Exception e) {
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, e.getMessage());
            log.warn("event=content.summary.failed postId={} reason=PORT_EXCEPTION", postId, e);
            throw new AmqpRejectAndDontRequeueException("post summary port failed", e);
        }

        if (summary == null || summary.isBlank()) {
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            consumerRecordService.markFail(event.getEventId(), CONSUMER_NAME, "EMPTY_SUMMARY");
            throw new AmqpRejectAndDontRequeueException("post summary empty");
        }

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

