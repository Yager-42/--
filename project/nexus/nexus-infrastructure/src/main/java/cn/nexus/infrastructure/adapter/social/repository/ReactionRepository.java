package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.infrastructure.dao.social.IInteractionReactionCountDao;
import cn.nexus.infrastructure.dao.social.IInteractionReactionDao;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionCountPO;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 点赞仓储 MyBatis 实现。
 *
 * @author codex
 * @since 2026-01-20
 */
@Repository
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
public class ReactionRepository implements IReactionRepository {

    private static final int BATCH_SIZE = 500;

    private final IInteractionReactionDao reactionDao;
    private final IInteractionReactionCountDao reactionCountDao;

    @Override
    public void batchUpsert(ReactionTargetVO target, List<Long> userIds) {
        if (target == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        String targetType = target.getTargetType().getCode();
        Long targetId = target.getTargetId();
        String reactionType = target.getReactionType().getCode();

        for (int i = 0; i < userIds.size(); i += BATCH_SIZE) {
            int end = Math.min(userIds.size(), i + BATCH_SIZE);
            List<InteractionReactionPO> batch = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                Long userId = userIds.get(j);
                if (userId == null) {
                    continue;
                }
                InteractionReactionPO po = new InteractionReactionPO();
                po.setTargetType(targetType);
                po.setTargetId(targetId);
                po.setReactionType(reactionType);
                po.setUserId(userId);
                batch.add(po);
            }
            if (!batch.isEmpty()) {
                reactionDao.batchUpsert(batch);
            }
        }
    }

    @Override
    public void batchDelete(ReactionTargetVO target, List<Long> userIds) {
        if (target == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        String targetType = target.getTargetType().getCode();
        Long targetId = target.getTargetId();
        String reactionType = target.getReactionType().getCode();

        for (int i = 0; i < userIds.size(); i += BATCH_SIZE) {
            int end = Math.min(userIds.size(), i + BATCH_SIZE);
            List<Long> batch = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                Long userId = userIds.get(j);
                if (userId != null) {
                    batch.add(userId);
                }
            }
            if (!batch.isEmpty()) {
                reactionDao.batchDelete(targetType, targetId, reactionType, batch);
            }
        }
    }

    @Override
    public void upsertCount(ReactionTargetVO target, long count) {
        if (target == null) {
            return;
        }
        InteractionReactionCountPO po = new InteractionReactionCountPO();
        po.setTargetType(target.getTargetType().getCode());
        po.setTargetId(target.getTargetId());
        po.setReactionType(target.getReactionType().getCode());
        po.setCount(Math.max(0L, count));
        reactionCountDao.insertOrUpdate(po);
    }
}

