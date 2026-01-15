package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.ICommunityApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.community.dto.*;
import cn.nexus.domain.social.model.valobj.GroupJoinResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.ICommunityService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 社群接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/group")
public class CommunityController implements ICommunityApi {

    @Resource
    private ICommunityService communityService;

    @PostMapping("/join")
    @Override
    public Response<GroupJoinResponseDTO> join(@RequestBody GroupJoinRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        GroupJoinResultVO vo = communityService.join(requestDTO.getGroupId(), userId, requestDTO.getAnswers(), requestDTO.getInviteToken());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                GroupJoinResponseDTO.builder().status(vo.getStatus()).build());
    }

    @PostMapping("/member/kick")
    @Override
    public Response<OperationResultDTO> kick(@RequestBody GroupKickRequestDTO requestDTO) {
        OperationResultVO vo = communityService.kick(requestDTO.getGroupId(), requestDTO.getTargetId(), requestDTO.getReason(), requestDTO.getBan());
        return toOperationResult(vo);
    }

    @PostMapping("/member/role")
    @Override
    public Response<OperationResultDTO> changeRole(@RequestBody GroupRoleRequestDTO requestDTO) {
        OperationResultVO vo = communityService.changeRole(requestDTO.getGroupId(), requestDTO.getTargetId(), requestDTO.getRoleId());
        return toOperationResult(vo);
    }

    @PostMapping("/channel/config")
    @Override
    public Response<OperationResultDTO> channelConfig(@RequestBody ChannelConfigRequestDTO requestDTO) {
        OperationResultVO vo = communityService.channelConfig(requestDTO.getChannelId(), requestDTO.getSlowModeInterval(), requestDTO.getLocked());
        return toOperationResult(vo);
    }

    private Response<OperationResultDTO> toOperationResult(OperationResultVO vo) {
        OperationResultDTO dto = OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
