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
        if (!isEffectivePostCounterEvent(event)) {
            return;
        }
        Long postId = event.getTargetId();
        long delta = event.getDelta();
        if (postId == null || delta == 0) {
            return;
        }
        ObjectCounterType metric = ObjectCounterType.fromCode(event.getMetric());
        incrementReceivedBestEffort(event.getRequestId(), postId, metric, delta);
        applyFeedSideEffectsBestEffort(postId, metric, delta);
    }

    private boolean isEffectivePostCounterEvent(CounterEvent event) {
        return event != null
                && (ObjectCounterType.LIKE.getCode().equals(event.getMetric())
                || ObjectCounterType.FAV.getCode().equals(event.getMetric()))
                && "post".equals(event.getTargetType())
                && event.getTargetId() != null
                && event.getDelta() != 0;
    }

    private void incrementReceivedBestEffort(String requestId, Long postId, ObjectCounterType metric, long delta) {
        try {
            Long ownerUserId = postAuthorPort.getPostAuthorId(postId);
            if (ownerUserId == null) {
                return;
            }
            if (metric == ObjectCounterType.LIKE) {
                userCounterService.incrementLikesReceived(ownerUserId, delta);
            } else if (metric == ObjectCounterType.FAV) {
                userCounterService.incrementFavsReceived(ownerUserId, delta);
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("increment received counter failed, requestId={}, postId={}, metric={}, delta={}",
                        requestId, postId, metric, delta, e);
            }
        }
    }

    private void applyFeedSideEffectsBestEffort(Long postId, ObjectCounterType metric, long delta) {
        try {
            feedCounterSideEffectPort.applyPostCounterDelta(postId, metric, delta);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("apply feed side effects failed, postId={}, metric={}, delta={}", postId, metric, delta, e);
            }
        }
    }
}
