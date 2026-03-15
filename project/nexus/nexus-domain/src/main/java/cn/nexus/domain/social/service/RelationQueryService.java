package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.RelationListVO;
import cn.nexus.domain.social.model.valobj.RelationStateBatchVO;
import cn.nexus.domain.social.model.valobj.RelationUserEdgeVO;
import cn.nexus.domain.social.model.valobj.RelationUserVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 关系读侧查询服务。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-08
 */
@Service
@RequiredArgsConstructor
public class RelationQueryService {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_BLOCK = 3;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final IUserBaseRepository userBaseRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IRelationRepository relationRepository;

    /**
     * 查询关注列表。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param cursor 翻页游标，类型：{@link String}
     * @param limit 页大小，类型：{@link Integer}
     * @return 关注列表结果，类型：{@link RelationListVO}
     */
    public RelationListVO following(Long userId, String cursor, Integer limit) {
        List<RelationUserEdgeVO> rows = relationAdjacencyCachePort.pageFollowing(userId, cursor, normalizeLimit(limit));
        return toListVO(rows);
    }

    /**
     * 查询粉丝列表。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param cursor 翻页游标，类型：{@link String}
     * @param limit 页大小，类型：{@link Integer}
     * @return 粉丝列表结果，类型：{@link RelationListVO}
     */
    public RelationListVO followers(Long userId, String cursor, Integer limit) {
        List<RelationUserEdgeVO> rows = relationAdjacencyCachePort.pageFollowers(userId, cursor, normalizeLimit(limit));
        return toListVO(rows);
    }

    /**
     * 批量查询关注态与屏蔽态。
     *
     * @param sourceId 发起人 ID，类型：{@link Long}
     * @param targetUserIds 目标用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 批量关系态结果，类型：{@link RelationStateBatchVO}
     */
    public RelationStateBatchVO batchState(Long sourceId, List<Long> targetUserIds) {
        if (sourceId == null || targetUserIds == null || targetUserIds.isEmpty()) {
            return RelationStateBatchVO.builder().followingUserIds(List.of()).blockedUserIds(List.of()).build();
        }
        List<Long> deduped = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        // 先做去重，保证后面批量查库和返回顺序都稳定。
        for (Long targetId : targetUserIds) {
            if (targetId != null && seen.add(targetId)) {
                deduped.add(targetId);
            }
        }
        if (deduped.isEmpty()) {
            return RelationStateBatchVO.builder().followingUserIds(List.of()).blockedUserIds(List.of()).build();
        }
        Set<Long> following = new HashSet<>(relationRepository.batchFindActiveFollowTargets(sourceId, deduped));
        Set<Long> blockFromMe = new HashSet<>(relationRepository.batchFindBlockTargetsBySource(sourceId, deduped));
        Set<Long> blockToMe = new HashSet<>(relationRepository.batchFindBlockSourcesByTarget(sourceId, deduped));
        List<Long> blocked = new ArrayList<>();
        // 屏蔽是双向语义：任意一边拉黑，都视为当前不可见。
        for (Long targetId : deduped) {
            if (blockFromMe.contains(targetId) || blockToMe.contains(targetId)) {
                blocked.add(targetId);
            }
        }
        List<Long> followingList = new ArrayList<>();
        for (Long targetId : deduped) {
            if (following.contains(targetId)) {
                followingList.add(targetId);
            }
        }
        return RelationStateBatchVO.builder()
                .followingUserIds(followingList)
                .blockedUserIds(blocked)
                .build();
    }

    /**
     * 批量查询“我是否关注了这些人”。
     *
     * @param sourceId 发起人 ID，类型：{@link Long}
     * @param targetUserIds 目标用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 已关注用户 ID 集合，类型：{@link Set}&lt;{@link Long}&gt;
     */
    public Set<Long> batchFollowing(Long sourceId, List<Long> targetUserIds) {
        RelationStateBatchVO state = batchState(sourceId, targetUserIds);
        return new HashSet<>(state.getFollowingUserIds() == null ? List.of() : state.getFollowingUserIds());
    }

    private RelationListVO toListVO(List<RelationUserEdgeVO> edges) {
        if (edges == null || edges.isEmpty()) {
            return RelationListVO.builder().items(List.of()).nextCursor(null).build();
        }
        List<Long> userIds = new ArrayList<>(edges.size());
        for (RelationUserEdgeVO edge : edges) {
            if (edge != null && edge.getUserId() != null) {
                userIds.add(edge.getUserId());
            }
        }
        Map<Long, UserBriefVO> briefMap = new HashMap<>();
        // 先批量补昵称头像，再按边的顺序回填，避免 N+1。
        for (UserBriefVO brief : userBaseRepository.listByUserIds(userIds)) {
            if (brief != null && brief.getUserId() != null) {
                briefMap.put(brief.getUserId(), brief);
            }
        }
        List<RelationUserVO> items = new ArrayList<>(edges.size());
        for (RelationUserEdgeVO edge : edges) {
            if (edge == null || edge.getUserId() == null) {
                continue;
            }
            UserBriefVO brief = briefMap.get(edge.getUserId());
            items.add(RelationUserVO.builder()
                    .userId(edge.getUserId())
                    .nickname(brief == null ? null : brief.getNickname())
                    .avatarUrl(brief == null ? null : brief.getAvatarUrl())
                    .followTime(edge.getFollowTimeMs())
                    .build());
        }
        RelationUserVO last = items.isEmpty() ? null : items.get(items.size() - 1);
        String nextCursor = last == null || last.getFollowTime() == null || last.getUserId() == null
                ? null
                : last.getFollowTime() + ":" + last.getUserId();
        return RelationListVO.builder().items(items).nextCursor(nextCursor).build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

}
