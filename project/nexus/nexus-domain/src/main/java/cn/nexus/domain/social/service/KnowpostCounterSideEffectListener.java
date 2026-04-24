package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.event.CounterEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IFeedCounterSideEffectPort;
import cn.nexus.domain.social.adapter.port.IReactionLikeUnlikeMqPort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
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
    private final IUserCounterPort userCounterPort;
    private final IFeedCounterSideEffectPort feedCounterSideEffectPort;
    private final IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort;

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
        publishLikeUnlikeEventBestEffort(event, postId, delta);
        incrementLikeReceivedBestEffort(event.getRequestId(), postId, delta);
        applyFeedSideEffectsBestEffort(postId, delta);
    }

    private boolean isEffectivePostLikeEvent(CounterEvent event) {
        return event != null
                && event.getCounterType() == ObjectCounterType.LIKE
                && event.getTargetType() == ReactionTargetTypeEnumVO.POST
                && event.getTargetId() != null
                && event.getDelta() != 0;
    }

    private void incrementLikeReceivedBestEffort(String requestId, Long postId, long delta) {
        try {
            Long ownerUserId = postAuthorPort.getPostAuthorId(postId);
            if (ownerUserId == null) {
                return;
            }
            userCounterPort.increment(ownerUserId, UserCounterType.LIKE_RECEIVED, delta);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("increment like_received failed, requestId={}, postId={}, delta={}", requestId, postId, delta, e);
            }
        }
    }

    private void publishLikeUnlikeEventBestEffort(CounterEvent counterEvent, Long postId, long delta) {
        try {
            Long ownerUserId = postAuthorPort.getPostAuthorId(postId);
            if (ownerUserId == null) {
                return;
            }
            LikeUnlikePostEvent event = new LikeUnlikePostEvent();
            event.setEventId(counterEvent.getRequestId());
            event.setUserId(counterEvent.getActorUserId());
            event.setPostId(postId);
            event.setPostCreatorId(ownerUserId);
            event.setCreateTime(counterEvent.getTsMs());
            if (delta > 0) {
                event.setType(1);
                reactionLikeUnlikeMqPort.publishLike(event);
                return;
            }
            event.setType(0);
            reactionLikeUnlikeMqPort.publishUnlike(event);
        } catch (Exception e) {
            log.warn("publish like/unlike event failed, requestId={}, postId={}, delta={}",
                    counterEvent.getRequestId(), postId, delta, e);
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
