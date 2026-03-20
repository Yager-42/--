package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskEventVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;

/**
 * 风控服务：承载文本、图片和用户能力状态的统一读取与判定。
 *
 * @author rr
 * @author codex
 * @author {$authorName}
 * @since 2025-12-26
 */
public interface IRiskService {

    /**
     * 统一风控决策入口：对 `RiskEvent` 给出 `RiskDecision`。
     *
     * @param event 风控事件，类型： {@link RiskEventVO}
     * @return 风控决策结果，类型： {@link RiskDecisionVO}
     */
    RiskDecisionVO decision(RiskEventVO event);

    /**
     * 扫描文本内容。
     *
     * @param content 待扫描文本，类型： {@link String}
     * @param userId 操作用户 ID，类型： {@link Long}
     * @param scenario 业务场景标识，类型： {@link String}
     * @return 文本扫描结果，类型： {@link TextScanResultVO}
     */
    TextScanResultVO textScan(String content, Long userId, String scenario);

    /**
     * 扫描图片内容。
     *
     * @param imageUrl 图片 URL，类型： {@link String}
     * @param userId 操作用户 ID，类型： {@link Long}
     * @return 图片扫描结果，类型： {@link ImageScanResultVO}
     */
    ImageScanResultVO imageScan(String imageUrl, Long userId);

    /**
     * 查询用户风控状态。
     *
     * @param userId 用户 ID，类型： {@link Long}
     * @return 用户风控状态，类型： {@link UserRiskStatusVO}
     */
    UserRiskStatusVO userStatus(Long userId);
}
