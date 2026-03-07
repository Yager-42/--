package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.ReactionLikersVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;

public interface IReactionLikeService {

    ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId);

    void syncTarget(ReactionTargetVO target);

    ReactionStateVO queryState(Long userId, ReactionTargetVO target);

    ReactionLikersVO queryLikers(ReactionTargetVO target, String cursor, Integer limit);
}
