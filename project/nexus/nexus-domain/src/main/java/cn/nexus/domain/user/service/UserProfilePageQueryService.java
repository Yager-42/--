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
 * 个人主页聚合 Query Service：把用户域 + 关系域 + 风控域的读逻辑在这里编排。
 *
 * <p>注意：这是“读侧聚合”，不要把关系/风控逻辑塞进 UserService（写侧命令服务）。</p>
 */
@Service
@RequiredArgsConstructor
public class UserProfilePageQueryService {

    private static final int RELATION_FOLLOW = 1;
    private static final int RELATION_FRIEND = 2;

    private final IUserProfileRepository userProfileRepository;
    private final IUserStatusRepository userStatusRepository;
    private final IRelationRepository relationRepository;
    private final IRelationCachePort relationCachePort;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRiskService riskService;

    public UserProfilePageVO query(Long viewerId, Long targetUserId) {
        if (viewerId == null || targetUserId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "viewerId/targetUserId 不能为空");
        }
        if (!viewerId.equals(targetUserId)) {
            // 最小隐私：任一方向屏蔽 => NOT_FOUND（不泄露用户存在性）
            if (relationPolicyPort.isBlocked(viewerId, targetUserId) || relationPolicyPort.isBlocked(targetUserId, viewerId)) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
        }

        UserProfileVO profile = userProfileRepository.get(targetUserId);
        if (profile == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        String status = userStatusRepository.getStatus(targetUserId);
        long followCount = relationCachePort.getFollowCount(targetUserId);
        long followerCount = relationRepository.countFollowerIds(targetUserId);
        long friendCount = relationRepository.countRelationsBySource(targetUserId, RELATION_FRIEND);

        boolean isFollow = false;
        if (!viewerId.equals(targetUserId)) {
            RelationEntity followEdge = relationRepository.findRelation(viewerId, targetUserId, RELATION_FOLLOW);
            RelationEntity friendEdge = relationRepository.findRelation(viewerId, targetUserId, RELATION_FRIEND);
            isFollow = followEdge != null || friendEdge != null;
        }

        UserRiskStatusVO risk = riskService.userStatus(targetUserId);
        UserRelationStatsVO relation = UserRelationStatsVO.builder()
                .followCount(followCount)
                .followerCount(followerCount)
                .friendCount(friendCount)
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

