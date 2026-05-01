package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostActionService implements IPostActionService {

    private final IObjectCounterService objectCounterService;
    private final ISocialIdPort socialIdPort;
    private final IReactionNotifyMqPort reactionNotifyMqPort;
    private final IReactionRecommendFeedbackMqPort reactionRecommendFeedbackMqPort;
    private final IPostAuthorPort postAuthorPort;

    @Override
    public PostActionResultVO likePost(Long postId, Long userId, String requestId) {
        requireIds(postId, userId);
        PostActionResultVO result = apply(() -> objectCounterService.likePost(postId, userId));
        if (result.isChanged()) {
            publishPostLiked(requestId, userId, postId);
        }
        return result;
    }

    @Override
    public PostActionResultVO unlikePost(Long postId, Long userId, String requestId) {
        requireIds(postId, userId);
        PostActionResultVO result = apply(() -> objectCounterService.unlikePost(postId, userId));
        if (result.isChanged()) {
            publishRecommendUnlike(requestId, userId, postId);
        }
        return result;
    }

    @Override
    public PostActionResultVO favPost(Long postId, Long userId, String requestId) {
        requireIds(postId, userId);
        return apply(() -> objectCounterService.favPost(postId, userId));
    }

    @Override
    public PostActionResultVO unfavPost(Long postId, Long userId, String requestId) {
        requireIds(postId, userId);
        return apply(() -> objectCounterService.unfavPost(postId, userId));
    }

    private PostActionResultVO apply(ActionSupplier supplier) {
        try {
            return supplier.get();
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo(), e);
        }
    }

    private void publishPostLiked(String requestId, Long fromUserId, Long postId) {
        try {
            Long creatorId = postAuthorPort.getPostAuthorId(postId);
            if (creatorId == null) {
                return;
            }
            String rid = normalizedRequestId(requestId);
            InteractionNotifyEvent event = new InteractionNotifyEvent();
            event.setEventType(EventType.LIKE_ADDED);
            event.setEventId(rid.isBlank() ? ("notify:like:" + socialIdPort.nextId()) : ("notify:like:" + rid));
            event.setRequestId(rid.isBlank() ? null : rid);
            event.setFromUserId(fromUserId);
            event.setTargetType("POST");
            event.setTargetId(postId);
            event.setPostId(postId);
            event.setTsMs(socialIdPort.now());
            reactionNotifyMqPort.publish(event);
        } catch (Exception e) {
            log.warn("publish post like notification failed, requestId={}, fromUserId={}, postId={}",
                    requestId, fromUserId, postId, e);
        }
    }

    private void publishRecommendUnlike(String requestId, Long fromUserId, Long postId) {
        try {
            String rid = normalizedRequestId(requestId);
            RecommendFeedbackEvent event = new RecommendFeedbackEvent();
            event.setEventId(rid.isBlank() ? ("recommend:unlike:" + socialIdPort.nextId()) : ("recommend:unlike:" + rid));
            event.setFromUserId(fromUserId);
            event.setPostId(postId);
            event.setFeedbackType("unlike");
            event.setTsMs(socialIdPort.now());
            reactionRecommendFeedbackMqPort.publish(event);
        } catch (Exception e) {
            log.warn("publish recommend unlike failed, requestId={}, fromUserId={}, postId={}",
                    requestId, fromUserId, postId, e);
        }
    }

    private String normalizedRequestId(String requestId) {
        return requestId == null ? "" : requestId.trim();
    }

    private void requireIds(Long postId, Long userId) {
        if (postId == null || userId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
    }

    @FunctionalInterface
    private interface ActionSupplier {
        PostActionResultVO get();
    }
}
