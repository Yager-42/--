package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import cn.nexus.infrastructure.support.SingleFlight;
import org.springframework.stereotype.Repository;

/**
 * FeedCardStatRepository 仓储实现。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-08
 */
@Repository
@RequiredArgsConstructor
public class FeedCardStatRepository implements IFeedCardStatRepository {
    private final IReactionCachePort reactionCachePort;
    private final SingleFlight singleFlight = new SingleFlight();

    /**
     * 执行 getBatch 逻辑。
     *
     * @param postIds postIds 参数。类型：{@link List}
     * @return 处理结果。类型：{@link Map}
     */
    @Override
    public Map<Long, FeedCardStatVO> getBatch(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        List<ReactionTargetVO> targets = new java.util.ArrayList<>(postIds.size());
        for (Long postId : postIds) {
            if (postId == null) {
                continue;
            }
            targets.add(ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build());
        }
        if (targets.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> countByTag = reactionCachePort.batchGetCount(targets);
        Map<Long, FeedCardStatVO> result = new HashMap<>(targets.size());
        for (ReactionTargetVO target : targets) {
            Long count = countByTag.get(target.hashTag());
            result.put(target.getTargetId(), FeedCardStatVO.builder()
                    .postId(target.getTargetId())
                    .likeCount(count == null ? 0L : count)
                    .build());
        }
        return result;
    }

    /**
     * 执行 getOrLoadBatch 逻辑。
     *
     * @param postIds postIds 参数。类型：{@link List}
     * @param loader loader 参数。类型：{@link Function}
     * @return 处理结果。类型：{@link Map}
     */
    @Override
    public Map<Long, FeedCardStatVO> getOrLoadBatch(List<Long> postIds,
                                                    Function<List<Long>, Map<Long, FeedCardStatVO>> loader) {
        List<Long> missIds = normalizeIds(postIds);
        if (missIds.isEmpty() || loader == null) {
            return Map.of();
        }
        Map<Long, FeedCardStatVO> loaded = singleFlight.execute(
                normalizeInflightKey(missIds),
                () -> loader.apply(missIds)
        );
        return loaded == null || loaded.isEmpty() ? Map.of() : loaded;
    }

    /**
     * 执行 saveBatch 逻辑。
     *
     * @param stats stats 参数。类型：{@link List}
     */
    @Override
    public void saveBatch(List<FeedCardStatVO> stats) {
        // 点赞计数由 ReactionCachePort 统一维护。
    }

    /**
     * 执行 evictLocal 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     */
    public void evictLocal(Long postId) {
    }

    /**
     * 执行 evictRedis 逻辑。
     *
     * @param postId 帖子 ID。类型：{@link Long}
     */
    public void evictRedis(Long postId) {
    }

    private List<Long> normalizeIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (Long postId : postIds) {
            if (postId == null || !seen.add(postId)) {
                continue;
            }
            ids.add(postId);
        }
        return ids;
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                normalized.add(id);
            }
        }
        return normalized.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }
}
