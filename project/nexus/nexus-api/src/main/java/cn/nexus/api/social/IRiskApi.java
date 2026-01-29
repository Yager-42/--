package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.risk.dto.*;

/**
 * 风控接口定义。
 */
public interface IRiskApi {

    Response<RiskDecisionResponseDTO> decision(RiskDecisionRequestDTO requestDTO);

    Response<TextScanResponseDTO> textScan(TextScanRequestDTO requestDTO);

    Response<ImageScanResponseDTO> imageScan(ImageScanRequestDTO requestDTO);

    Response<UserRiskStatusResponseDTO> userStatus(UserRiskStatusRequestDTO requestDTO);

    /** 用户发起申诉 */
    Response<RiskAppealResponseDTO> appeal(RiskAppealRequestDTO requestDTO);
}
