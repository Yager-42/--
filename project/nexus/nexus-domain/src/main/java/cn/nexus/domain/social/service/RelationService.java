package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 关系领域服务实现，使用占位逻辑保证接口可用。
 */
@Service
@RequiredArgsConstructor
public class RelationService implements IRelationService {

    private final ISocialIdPort socialIdPort;

    @Override
    public FollowResultVO follow(Long sourceId, Long targetId) {
        String status = sourceId != null && targetId != null && sourceId % 2 == 0 ? "PENDING" : "ACTIVE";
        return FollowResultVO.builder().status(status).build();
    }

    @Override
    public FriendRequestResultVO friendRequest(Long sourceId, Long targetId, String verifyMsg, String sourceChannel) {
        return FriendRequestResultVO.builder()
                .requestId(socialIdPort.nextId())
                .status("PENDING")
                .build();
    }

    @Override
    public FriendDecisionResultVO friendDecision(Long requestId, String action) {
        boolean accepted = "ACCEPT".equalsIgnoreCase(action);
        return FriendDecisionResultVO.builder().success(accepted).build();
    }

    @Override
    public OperationResultVO block(Long sourceId, Long targetId) {
        return OperationResultVO.builder()
                .success(true)
                .id(socialIdPort.nextId())
                .status("BLOCKED")
                .message("已屏蔽")
                .build();
    }

    @Override
    public RelationGroupVO manageGroup(Long userId, String action, String listName, Long listId, List<Long> memberIds) {
        Long id = listId != null ? listId : socialIdPort.nextId();
        List<Long> members = memberIds == null ? Collections.emptyList() : memberIds;
        String name = listName != null ? listName : "默认分组";
        return RelationGroupVO.builder()
                .listId(id)
                .listName(name)
                .memberIds(members)
                .build();
    }
}
