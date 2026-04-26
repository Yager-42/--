package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.relation.dto.BlockRequestDTO;
import cn.nexus.api.social.relation.dto.BlockResponseDTO;
import cn.nexus.api.social.relation.dto.FollowRequestDTO;
import cn.nexus.api.social.relation.dto.FollowResponseDTO;
import cn.nexus.api.social.relation.dto.RelationCounterResponseDTO;
import cn.nexus.api.social.relation.dto.RelationListRequestDTO;
import cn.nexus.api.social.relation.dto.RelationListResponseDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchRequestDTO;
import cn.nexus.api.social.relation.dto.RelationStateBatchResponseDTO;

/**
 * 用户关系相关接口定义。
 */
public interface IRelationApi {

    Response<FollowResponseDTO> follow(FollowRequestDTO requestDTO);

    Response<FollowResponseDTO> unfollow(FollowRequestDTO requestDTO);

    Response<BlockResponseDTO> block(BlockRequestDTO requestDTO);

    Response<RelationCounterResponseDTO> counter();

    Response<RelationListResponseDTO> following(RelationListRequestDTO requestDTO);

    Response<RelationListResponseDTO> followers(RelationListRequestDTO requestDTO);

    Response<RelationStateBatchResponseDTO> stateBatch(RelationStateBatchRequestDTO requestDTO);
}
