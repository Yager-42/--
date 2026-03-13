package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskEventVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;

/**
 * 风控服务。
 */
public interface IRiskService {

    /**
     * 统一风控决策入口：对 RiskEvent 给出 RiskDecision。
     */
    RiskDecisionVO decision(RiskEventVO event);

    TextScanResultVO textScan(String content, Long userId, String scenario);

    ImageScanResultVO imageScan(String imageUrl, Long userId);

    UserRiskStatusVO userStatus(Long userId);
}
