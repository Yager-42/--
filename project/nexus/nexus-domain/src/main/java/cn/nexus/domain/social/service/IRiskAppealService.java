package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.OperationResultVO;

/**
 * 风控申诉服务：用户提交申诉 + 后台处理申诉。
 */
public interface IRiskAppealService {

    OperationResultVO submitAppeal(Long userId, Long decisionId, Long punishId, String content);

    OperationResultVO decideAppeal(Long operatorId, Long appealId, String result);
}

