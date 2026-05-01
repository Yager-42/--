package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Local knowpost-scoped side effects for counter changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowpostCounterSideEffectListener {

    private final IPostAuthorPort postAuthorPort;
    private final IUserCounterService userCounterService;
    private final IFeedCounterSideEffectPort feedCounterSideEffectPort;

    /**
     * Handle local post-like delta.
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!isEffectivePostLikeEvent(event)) {
            return;
        }
        Long postId = event.getTargetId();
        long delta = event.getDelta();
        if (postId == null || delta == 0) {
            return;
        }
        incrementLikeReceivedBestEffort(event.getRequestId(), postId, delta);
        applyFeedSideEffectsBestEffort(postId, delta);
    }

    private boolean isEffectivePostLikeEvent(CounterEvent event) {
        return event != null
                && ObjectCounterType.LIKE.getCode().equals(event.getMetric())
                && "post".equals(event.getTargetType())
                && event.getTargetId() != null
                && event.getDelta() != 0;
    }

    private void incrementLikeReceivedBestEffort(String requestId, Long postId, long delta) {
        try {
            Long ownerUserId = postAuthorPort.getPostAuthorId(postId);
            if (ownerUserId == null) {
                return;
            }
            userCounterService.incrementLikesReceived(ownerUserId, delta);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("increment like_received failed, requestId={}, postId={}, delta={}", requestId, postId, delta, e);
            }
        }
    }

    private void applyFeedSideEffectsBestEffort(Long postId, long delta) {
        try {
            feedCounterSideEffectPort.applyPostLikeDelta(postId, delta);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("apply feed side effects failed, postId={}, delta={}", postId, delta, e);
            }
        }
    }
}
