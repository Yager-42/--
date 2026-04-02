package cn.nexus.domain.user.service;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 个人主页聚合 `Query Service`：把用户域、关系域和风控域的只读数据编排到一起。
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Service
public class UserProfilePageQueryService {

    /**
     * 关注关系类型。
     */
    private static final int RELATION_FOLLOW = 1;

    private final IUserProfileRepository userProfileRepository;
    private final IUserStatusRepository userStatusRepository;
    private final IRelationRepository relationRepository;
    private final IUserCounterPort userCounterPort;
    private final IRelationPolicyPort relationPolicyPort;
    private final IRiskService riskService;
    private final Executor aggregationExecutor;

    public UserProfilePageQueryService(IUserProfileRepository userProfileRepository,
                                      IUserStatusRepository userStatusRepository,
                                      IRelationRepository relationRepository,
                                      IUserCounterPort userCounterPort,
                                      IRelationPolicyPort relationPolicyPort,
                                      IRiskService riskService,
                                      @Qualifier("aggregationExecutor") Executor aggregationExecutor) {
        this.userProfileRepository = userProfileRepository;
        this.userStatusRepository = userStatusRepository;
        this.relationRepository = relationRepository;
        this.userCounterPort = userCounterPort;
        this.relationPolicyPort = relationPolicyPort;
        this.riskService = riskService;
        this.aggregationExecutor = aggregationExecutor;
    }

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

        CompletableFuture<String> statusFuture = CompletableFuture.supplyAsync(
                () -> userStatusRepository.getStatus(targetUserId),
                aggregationExecutor);
        CompletableFuture<Long> followCountFuture = CompletableFuture.supplyAsync(
                () -> userCounterPort.getCount(targetUserId, UserCounterType.FOLLOWING),
                aggregationExecutor);
        CompletableFuture<Long> followerCountFuture = CompletableFuture.supplyAsync(
                () -> userCounterPort.getCount(targetUserId, UserCounterType.FOLLOWER),
                aggregationExecutor);
        CompletableFuture<Boolean> isFollowFuture = viewerId.equals(targetUserId)
                ? CompletableFuture.completedFuture(false)
                : CompletableFuture.supplyAsync(() -> {
                    // `isFollow` 只在“看别人主页”时才有意义，看自己主页不用去关系表多查一次
                    RelationEntity followEdge = relationRepository.findRelation(viewerId, targetUserId, RELATION_FOLLOW);
                    return followEdge != null && Integer.valueOf(1).equals(followEdge.getStatus());
                }, aggregationExecutor);
        CompletableFuture<UserRiskStatusVO> riskFuture = CompletableFuture.supplyAsync(
                () -> riskService.userStatus(targetUserId),
                aggregationExecutor);

        try {
            CompletableFuture.allOf(statusFuture, followCountFuture, followerCountFuture, isFollowFuture, riskFuture)
                    .join();
        } catch (CompletionException e) {
            throw unwrapCompletionException(e);
        }

        String status = statusFuture.join();
        long followCount = followCountFuture.join();
        long followerCount = followerCountFuture.join();
        boolean isFollow = isFollowFuture.join();
        UserRiskStatusVO risk = riskFuture.join();
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

    private static RuntimeException unwrapCompletionException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new RuntimeException(cause);
    }
}
