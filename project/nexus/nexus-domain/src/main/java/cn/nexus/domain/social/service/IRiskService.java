package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;

/**
 * 风控服务。
 */
public interface IRiskService {

    TextScanResultVO textScan(String content, Long userId, String scenario);

    ImageScanResultVO imageScan(String imageUrl, Long userId);

    UserRiskStatusVO userStatus(Long userId);
}
