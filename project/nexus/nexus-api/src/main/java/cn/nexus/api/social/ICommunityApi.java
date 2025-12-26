package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.community.dto.*;

/**
 * 社群管理接口定义。
 */
public interface ICommunityApi {

    Response<GroupJoinResponseDTO> join(GroupJoinRequestDTO requestDTO);

    Response<OperationResultDTO> kick(GroupKickRequestDTO requestDTO);

    Response<OperationResultDTO> changeRole(GroupRoleRequestDTO requestDTO);

    Response<OperationResultDTO> channelConfig(ChannelConfigRequestDTO requestDTO);
}
