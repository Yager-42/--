package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.relation.dto.*;

/**
 * 用户关系相关接口定义。
 */
public interface IRelationApi {

    Response<FollowResponseDTO> follow(FollowRequestDTO requestDTO);

    /**
     * 取消关注用户。
     *
     * @param requestDTO 请求 {@link FollowRequestDTO}
     * @return 结果 {@link FollowResponseDTO}
     */
    Response<FollowResponseDTO> unfollow(FollowRequestDTO requestDTO);

    Response<FriendRequestResponseDTO> friendRequest(FriendRequestDTO requestDTO);

    Response<FriendDecisionResponseDTO> friendDecision(FriendDecisionRequestDTO requestDTO);

    Response<BlockResponseDTO> block(BlockRequestDTO requestDTO);

    Response<RelationGroupResponseDTO> manageGroup(RelationGroupRequestDTO requestDTO);
}
