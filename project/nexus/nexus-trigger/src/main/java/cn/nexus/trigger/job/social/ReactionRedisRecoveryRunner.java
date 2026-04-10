package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.adapter.social.repository.ReactionEventLogRepository;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReactionRedisRecoveryRunner implements ApplicationRunner {

    private final ReactionEventLogRepository reactionEventLogRepository;
    private final IReactionCachePort reactionCachePort;
    private final int pageSize;

    public ReactionRedisRecoveryRunner(ReactionEventLogRepository reactionEventLogRepository,
                                       IReactionCachePort reactionCachePort,
                                       @Value("${reaction.recovery.page-size:100}") int pageSize) {
        this.reactionEventLogRepository = reactionEventLogRepository;
        this.reactionCachePort = reactionCachePort;
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
        Long checkpoint = reactionCachePort.getRecoveryCheckpoint(targetType, reactionType);
        long cursor = checkpoint == null ? 0L : checkpoint;

        while (true) {
            List<InteractionReactionEventLogPO> page = reactionEventLogRepository.pageAfterSeq(targetType, reactionType, cursor, pageSize);
            if (page == null || page.isEmpty()) {
                return true;
            }

            long pageLastSeq = cursor;
            for (InteractionReactionEventLogPO event : page) {
                if (!applyEvent(event)) {
                    log.warn("reaction recovery stopped on failed page, targetType={}, reactionType={}, checkpoint={}, failedSeq={}",
                            targetType, reactionType, cursor, event == null ? null : event.getSeq());
                    return false;
                }
                if (event != null && event.getSeq() != null) {
                    pageLastSeq = event.getSeq();
                }
            }

            reactionCachePort.setRecoveryCheckpoint(targetType, reactionType, pageLastSeq);
            cursor = pageLastSeq;
        }
    }

    private boolean applyEvent(InteractionReactionEventLogPO event) {
        if (event == null || event.getUserId() == null || event.getTargetId() == null || event.getDesiredState() == null) {
            return false;
        }
        ReactionTargetTypeEnumVO targetType = ReactionTargetTypeEnumVO.from(event.getTargetType());
        ReactionTypeEnumVO reactionType = ReactionTypeEnumVO.from(event.getReactionType());
        if (targetType == null || reactionType == null) {
            return false;
        }
        ReactionTargetVO target = ReactionTargetVO.builder()
                .targetType(targetType)
                .targetId(event.getTargetId())
                .reactionType(reactionType)
                .build();
        return reactionCachePort.applyRecoveryEvent(
                event.getUserId(),
                target,
                event.getDesiredState());
    }
}
