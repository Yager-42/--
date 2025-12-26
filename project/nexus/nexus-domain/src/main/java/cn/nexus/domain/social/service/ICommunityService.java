package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.GroupJoinResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;

/**
 * 社群服务。
 */
public interface ICommunityService {

    GroupJoinResultVO join(Long groupId, Long userId, String answers, String inviteToken);

    OperationResultVO kick(Long groupId, Long targetId, String reason, Boolean ban);

    OperationResultVO changeRole(Long groupId, Long targetId, Long roleId);

    OperationResultVO channelConfig(Long channelId, Integer slowModeInterval, Boolean locked);
}
