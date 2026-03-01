package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IPostSummaryPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.trigger.mq.config.PostSummaryMqConfig;
import cn.nexus.types.event.PostSummaryGenerateEvent;
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

    private static final int SUMMARY_STATUS_DONE = 1;
    private static final int SUMMARY_STATUS_FAILED = 2;

    private final IContentRepository contentRepository;
    private final IPostSummaryPort postSummaryPort;

    @RabbitListener(queues = PostSummaryMqConfig.Q_POST_SUMMARY_GENERATE)
    public void onPostSummaryGenerate(PostSummaryGenerateEvent event) {
        if (event == null || event.getPostId() == null) {
            throw new AmqpRejectAndDontRequeueException("post.summary.generate missing postId");
        }
        Long postId = event.getPostId();
        ContentPostEntity post = contentRepository.findPost(postId);
        if (post == null) {
            log.info("event=content.summary.skip postId={} reason=POST_NOT_FOUND", postId);
            return;
        }

        Integer status = post.getSummaryStatus();
        String existed = post.getSummary();
        if (status != null && status == SUMMARY_STATUS_DONE && existed != null && !existed.isBlank()) {
            // 幂等：已经有摘要就直接跳过，避免重复调用模型。
            log.info("event=content.summary.skip postId={} reason=ALREADY_DONE", postId);
            return;
        }

        String summary;
        try {
            summary = postSummaryPort.summarize(post.getContentText(), post.getMediaInfo());
        } catch (Exception e) {
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            log.warn("event=content.summary.failed postId={} reason=PORT_EXCEPTION", postId, e);
            return;
        }

        if (summary == null || summary.isBlank()) {
            contentRepository.updatePostSummary(postId, null, SUMMARY_STATUS_FAILED);
            log.info("event=content.summary.failed postId={} reason=EMPTY_SUMMARY", postId);
            return;
        }

        boolean ok = contentRepository.updatePostSummary(postId, summary, SUMMARY_STATUS_DONE);
        log.info("event=content.summary.done postId={} ok={}", postId, ok);
    }
}

