package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
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

@Service
@RequiredArgsConstructor
public class RelationQueryService {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_BLOCK = 3;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IUserBaseRepository userBaseRepository;
    private final IRelationRepository relationRepository;

    public RelationListVO following(Long userId, String cursor, Integer limit) {
        List<RelationUserEdgeVO> edges = relationAdjacencyCachePort.pageFollowing(userId, cursor, normalizeLimit(limit));
        return toListVO(edges);
    }

    public RelationListVO followers(Long userId, String cursor, Integer limit) {
        List<RelationUserEdgeVO> edges = relationAdjacencyCachePort.pageFollowers(userId, cursor, normalizeLimit(limit));
        return toListVO(edges);
    }

    public RelationStateBatchVO batchState(Long sourceId, List<Long> targetUserIds) {
        if (sourceId == null || targetUserIds == null || targetUserIds.isEmpty()) {
            return RelationStateBatchVO.builder().followingUserIds(List.of()).blockedUserIds(List.of()).build();
        }
        List<Long> following = new ArrayList<>();
        List<Long> blocked = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long targetId : targetUserIds) {
            if (targetId == null || !seen.add(targetId)) {
                continue;
            }
            RelationEntity follow = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW);
            if (follow != null && Integer.valueOf(1).equals(follow.getStatus())) {
                following.add(targetId);
            }
            boolean blockedEitherSide = relationRepository.findRelation(sourceId, targetId, RELATION_BLOCK) != null
                    || relationRepository.findRelation(targetId, sourceId, RELATION_BLOCK) != null;
            if (blockedEitherSide) {
                blocked.add(targetId);
            }
        }
        return RelationStateBatchVO.builder()
                .followingUserIds(following)
                .blockedUserIds(blocked)
                .build();
    }

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
