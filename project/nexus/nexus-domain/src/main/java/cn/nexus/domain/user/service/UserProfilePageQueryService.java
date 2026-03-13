package cn.nexus.domain.user.service;

import cn.nexus.domain.social.adapter.port.IRelationCachePort;
import cn.nexus.domain.social.adapter.port.IRelationPolicyPort;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.model.valobj.UserProfilePageVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.model.valobj.UserRelationStatsVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 个人主页聚合 `Query Service`：把用户域、关系域和风控域的只读数据编排到一起。
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Service
@RequiredArgsConstructor
public class UserProfilePageQueryService {

    /**
     * 关注关系类型。
     */
    private static final int RELATION_FOLLOW = 1;

    private final IUserProfileRepository userProfileRepository;
    private final IUserStatusRepository userStatusRepository;
    private final IRelationRepository relationRepository;
    private final IRelationCachePort relationCachePort;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRiskService riskService;

    /**
     * 查询个人主页聚合视图。
     *
     * @param viewerId 当前查看者用户 ID，类型：{@link Long}
     * @param targetUserId 目标主页用户 ID，类型：{@link Long}
     * @return 主页聚合结果，类型：{@link UserProfilePageVO}
     */
    public UserProfilePageVO query(Long viewerId, Long targetUserId) {
        if (viewerId == null || targetUserId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "viewerId/targetUserId 不能为空");
        }
        if (!viewerId.equals(targetUserId)) {
            // 看别人主页时，先做双向拉黑判断；命中就直接伪装成“这个人不存在”
            if (relationPolicyPort.isBlocked(viewerId, targetUserId) || relationPolicyPort.isBlocked(targetUserId, viewerId)) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
        }

        // 用户域真值先拿齐，关系和风控再各自补自己的读模型
        UserProfileVO profile = userProfileRepository.get(targetUserId);
        if (profile == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        String status = userStatusRepository.getStatus(targetUserId);
        long followCount = relationCachePort.getFollowingCount(targetUserId);
        long followerCount = relationCachePort.getFollowerCount(targetUserId);

        boolean isFollow = false;
        if (!viewerId.equals(targetUserId)) {
            // `isFollow` 只在“看别人主页”时才有意义，看自己主页不用去关系表多查一次
            RelationEntity followEdge = relationRepository.findRelation(viewerId, targetUserId, RELATION_FOLLOW);
            isFollow = followEdge != null && Integer.valueOf(1).equals(followEdge.getStatus());
        }

        UserRiskStatusVO risk = riskService.userStatus(targetUserId);
        UserRelationStatsVO relation = UserRelationStatsVO.builder()
                .followCount(followCount)
                .followerCount(followerCount)
                .isFollow(isFollow)
                .build();
        return UserProfilePageVO.builder()
                .profile(profile)
                .status(status)
                .relation(relation)
                .risk(risk)
                .build();
    }
}
