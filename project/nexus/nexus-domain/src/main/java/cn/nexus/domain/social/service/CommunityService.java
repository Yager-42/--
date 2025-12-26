package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.GroupJoinResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 社群服务实现。
 */
@Service
@RequiredArgsConstructor
public class CommunityService implements ICommunityService {

    private final ISocialIdPort socialIdPort;

    @Override
    public GroupJoinResultVO join(Long groupId, Long userId, String answers, String inviteToken) {
        String status = inviteToken != null && !inviteToken.isBlank() ? "JOINED" : "PENDING";
        return GroupJoinResultVO.builder().status(status).build();
    }

    @Override
    public OperationResultVO kick(Long groupId, Long targetId, String reason, Boolean ban) {
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status(Boolean.TRUE.equals(ban) ? "BANNED" : "REMOVED")
                .message(reason)
                .build();
    }

    @Override
    public OperationResultVO changeRole(Long groupId, Long targetId, Long roleId) {
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("ROLE_CHANGED")
                .message("roleId=" + roleId)
                .build();
    }

    @Override
    public OperationResultVO channelConfig(Long channelId, Integer slowModeInterval, Boolean locked) {
        return OperationResultVO.builder()
                .success(true)
                .id(channelId)
                .status(Boolean.TRUE.equals(locked) ? "LOCKED" : "OPEN")
                .message("slow=" + slowModeInterval)
                .build();
    }
}
