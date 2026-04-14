package cn.nexus.trigger.job.social;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisKeys;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisSchema;
import cn.nexus.infrastructure.adapter.social.repository.ReactionEventLogRepository;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReactionRedisRecoveryRunner implements ApplicationRunner {

    private final ReactionEventLogRepository reactionEventLogRepository;
    private final StringRedisTemplate redisTemplate;
    private final IContentRepository contentRepository;
    private final ICommentRepository commentRepository;
    private final int pageSize;

    public ReactionRedisRecoveryRunner(ReactionEventLogRepository reactionEventLogRepository,
                                       StringRedisTemplate redisTemplate,
                                       IContentRepository contentRepository,
                                       ICommentRepository commentRepository,
                                       @Value("${reaction.recovery.page-size:100}") int pageSize) {
        this.reactionEventLogRepository = reactionEventLogRepository;
        this.redisTemplate = redisTemplate;
        this.contentRepository = contentRepository;
        this.commentRepository = commentRepository;
        this.pageSize = Math.max(1, pageSize);
    }

    @Override
    public void run(ApplicationArguments args) {
        recoverAll();
    }

    public void recoverAll() {
        recoverFamily("POST", "LIKE");
        recoverFamily("COMMENT", "LIKE");
    }

    public boolean recoverFamily(String targetType, String reactionType) {
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String checkpointKey = checkpointKey(targetType, reactionType);
        long cursor = operations.readReplayCheckpoint(checkpointKey);

        while (true) {
            List<InteractionReactionEventLogPO> page = reactionEventLogRepository.pageAfterSeq(targetType, reactionType, cursor, pageSize);
            if (page == null || page.isEmpty()) {
                return true;
            }

            long pageLastSeq = cursor;
            for (InteractionReactionEventLogPO event : page) {
                if (!applyEvent(event, operations)) {
                    log.warn("reaction recovery stopped on failed page, targetType={}, reactionType={}, checkpoint={}, failedSeq={}",
                            targetType, reactionType, cursor, event == null ? null : event.getSeq());
                    return false;
                }
                if (event != null && event.getSeq() != null) {
                    pageLastSeq = event.getSeq();
                }
            }

            operations.writeReplayCheckpoint(checkpointKey, pageLastSeq);
            cursor = pageLastSeq;
        }
    }

    private boolean applyEvent(InteractionReactionEventLogPO event, CountRedisOperations operations) {
        if (event == null || event.getUserId() == null || event.getTargetId() == null
                || event.getDesiredState() == null || event.getTargetType() == null || event.getReactionType() == null) {
            return false;
        }
        if (!"LIKE".equalsIgnoreCase(event.getReactionType())) {
            return false;
        }

        ReactionTargetTypeEnumVO targetType = ReactionTargetTypeEnumVO.from(event.getTargetType());
        if (targetType == null) {
            return false;
        }

        long userId = event.getUserId();
        long shard = userId / 1_000_000L;
        long offset = userId % 1_000_000L;
        String bitmapKey = CountRedisKeys.likeBitmapShard(targetType, event.getTargetId(), shard);
        boolean currentState = operations.readBitmapFact(bitmapKey, offset);
        boolean desiredState = event.getDesiredState() == 1;
        if (currentState == desiredState) {
            return true;
        }

        operations.writeBitmapFact(bitmapKey, offset, desiredState);

        ObjectCounterTarget counterTarget = ObjectCounterTarget.builder()
                .targetType(targetType)
                .targetId(event.getTargetId())
                .counterType(ObjectCounterType.LIKE)
                .build();
        String objectSnapshotKey = CountRedisKeys.objectSnapshot(counterTarget);
        Map<String, Long> objectSnapshot = operations.readObjectSnapshot(objectSnapshotKey, CountRedisSchema.forObject(targetType));
        long nextLikeCount = Math.max(0L, objectSnapshot.getOrDefault(ObjectCounterType.LIKE.getCode(), 0L) + delta(desiredState));
        objectSnapshot.put(ObjectCounterType.LIKE.getCode(), nextLikeCount);
        operations.writeObjectSnapshot(objectSnapshotKey, objectSnapshot, CountRedisSchema.forObject(targetType));

        Long ownerUserId = ownerUserId(event, targetType);
        if (ownerUserId == null) {
            return false;
        }
        String userSnapshotKey = CountRedisKeys.userSnapshot(ownerUserId);
        Map<String, Long> userSnapshot = operations.readUserSnapshot(userSnapshotKey, CountRedisSchema.user());
        long nextLikeReceived = Math.max(0L, userSnapshot.getOrDefault(UserCounterType.LIKE_RECEIVED.getCode(), 0L) + delta(desiredState));
        userSnapshot.put(UserCounterType.LIKE_RECEIVED.getCode(), nextLikeReceived);
        operations.writeUserSnapshot(userSnapshotKey, userSnapshot, CountRedisSchema.user());
        return true;
    }

    private Long ownerUserId(InteractionReactionEventLogPO event, ReactionTargetTypeEnumVO targetType) {
        if (targetType == ReactionTargetTypeEnumVO.POST) {
            ContentPostEntity post = contentRepository.findPost(event.getTargetId());
            return post == null ? null : post.getUserId();
        }
        if (targetType == ReactionTargetTypeEnumVO.COMMENT) {
            CommentBriefVO comment = commentRepository.getBrief(event.getTargetId());
            return comment == null ? null : comment.getUserId();
        }
        return null;
    }

    private int delta(boolean desiredState) {
        return desiredState ? 1 : -1;
    }

    private String checkpointKey(String targetType, String reactionType) {
        return "count:replay:checkpoint:" + (targetType == null ? "" : targetType) + ":" + (reactionType == null ? "" : reactionType);
    }
}
