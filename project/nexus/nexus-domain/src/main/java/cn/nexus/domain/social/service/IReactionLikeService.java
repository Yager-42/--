package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;

/**
 * 点赞子域服务：在线写入 + 延迟落库 + 状态查询。
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IReactionLikeService {

    /**
     * 在线写入：写 Redis 并按需投递延迟消息。
     *
     * @param userId    用户 ID {@link Long}
     * @param target    点赞目标 {@link ReactionTargetVO}
     * @param action    动作 {@link ReactionActionEnumVO}
     * @param requestId 请求标识（可选）{@link String}
     * @return {@link ReactionResultVO}
     */
    ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId);

    /**
     * 延迟落库：对某个 target 做一次同步（从 Redis ops 快照批量落库）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     */
    void syncTarget(ReactionTargetVO target);

    /**
     * 查询状态：我是否点过赞 + 当前近实时计数。
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@link ReactionStateVO}
     */
    ReactionStateVO queryState(Long userId, ReactionTargetVO target);
}

