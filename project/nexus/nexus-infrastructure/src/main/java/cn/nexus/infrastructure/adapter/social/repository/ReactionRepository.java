package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionUserEdgeVO;
import cn.nexus.infrastructure.dao.social.IInteractionReactionCountDao;
import cn.nexus.infrastructure.dao.social.IInteractionReactionCountDeltaInboxDao;
import cn.nexus.infrastructure.dao.social.IInteractionReactionDao;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionCountDeltaInboxPO;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionCountPO;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionPO;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReactionRepository 仓储实现。
 *
 * @author rr
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
    private final IInteractionReactionCountDeltaInboxDao reactionCountDeltaInboxDao;

    /**
     * 批量写入真相数据。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param userIds userIds 参数。类型：{@link List}
     */
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

    /**
     * 批量删除真相数据。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param userIds userIds 参数。类型：{@link List}
     */
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

    /**
     * 覆盖写入计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param count count 参数。类型：{@code long}
     */
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

    /**
     * 增量更新计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param delta delta 参数。类型：{@code long}
     */
    @Override
    public void incrCount(ReactionTargetVO target, long delta) {
        if (target == null || delta == 0) {
            return;
        }
        reactionCountDao.incrCount(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode(), delta);
    }

    /**
     * 按幂等键应用计数增量。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param eventId eventId 参数。类型：{@link String}
     * @param delta delta 参数。类型：{@code long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean applyCountDeltaOnce(ReactionTargetVO target, String eventId, long delta) {
        if (target == null || eventId == null || eventId.isBlank() || delta == 0) {
            return false;
        }
        InteractionReactionCountDeltaInboxPO po = new InteractionReactionCountDeltaInboxPO();
        po.setEventId(eventId.trim());
        int inserted = reactionCountDeltaInboxDao.insertIgnore(po);
        if (inserted <= 0) {
            return false;
        }
        incrCount(target, delta);
        return true;
    }

    /**
     * 判断数据是否存在。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean exists(ReactionTargetVO target, Long userId) {
        if (target == null || userId == null) {
            return false;
        }
        Integer flag = reactionDao.selectExists(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode(), userId);
        return flag != null && flag == 1;
    }

    /**
     * 幂等插入数据。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @return 处理结果。类型：{@code int}
     */
    @Override
    public int insertIgnore(ReactionTargetVO target, Long userId) {
        if (target == null || userId == null) {
            return 0;
        }
        return reactionDao.insertIgnore(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode(), userId);
    }

    /**
     * 删除单条数据。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @return 处理结果。类型：{@code int}
     */
    @Override
    public int deleteOne(ReactionTargetVO target, Long userId) {
        if (target == null || userId == null) {
            return 0;
        }
        return reactionDao.deleteOne(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode(), userId);
    }

    /**
     * 读取计数。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @return 处理结果。类型：{@code long}
     */
    @Override
    public long getCount(ReactionTargetVO target) {
        if (target == null) {
            return 0L;
        }
        Long count = reactionCountDao.selectCount(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode());
        return count == null ? 0L : Math.max(0L, count);
    }

    /**
     * 分页查询用户边。
     *
     * @param target target 参数。类型：{@link ReactionTargetVO}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<ReactionUserEdgeVO> pageUserEdgesByTarget(ReactionTargetVO target, String cursor, int limit) {
        if (target == null || limit <= 0) {
            return List.of();
        }
        Cursor parsed = Cursor.parse(cursor);
        Date cursorTime = parsed == null ? null : new Date(parsed.timeMs());
        Long cursorUserId = parsed == null ? null : parsed.userId();
        List<InteractionReactionPO> rows = reactionDao.pageByTarget(target.getTargetType().getCode(), target.getTargetId(), target.getReactionType().getCode(), cursorTime, cursorUserId, limit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ReactionUserEdgeVO> result = new ArrayList<>(rows.size());
        for (InteractionReactionPO row : rows) {
            if (row == null || row.getUserId() == null) {
                continue;
            }
            long likedAt = row.getUpdateTime() == null ? 0L : row.getUpdateTime().getTime();
            result.add(ReactionUserEdgeVO.builder().userId(row.getUserId()).likedAt(likedAt).build());
        }
        return result;
    }

    /**
     * 批量判断状态。
     *
     * @param targetTemplate targetTemplate 参数。类型：{@link ReactionTargetVO}
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param targetIds targetIds 参数。类型：{@link List}
     * @return 处理结果。类型：{@link Set}
     */
    @Override
    public Set<Long> batchExists(ReactionTargetVO targetTemplate, Long userId, List<Long> targetIds) {
        if (targetTemplate == null || userId == null || targetIds == null || targetIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = reactionDao.batchExists(targetTemplate.getTargetType().getCode(), targetTemplate.getReactionType().getCode(), userId, targetIds);
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    private record Cursor(long timeMs, long userId) {
        static Cursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.trim().split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
