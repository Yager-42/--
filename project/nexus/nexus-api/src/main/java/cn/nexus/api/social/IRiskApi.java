package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.risk.dto.*;

/**
 * 风控接口定义。
 */
public interface IRiskApi {

    Response<TextScanResponseDTO> textScan(TextScanRequestDTO requestDTO);

    Response<ImageScanResponseDTO> imageScan(ImageScanRequestDTO requestDTO);

    Response<UserRiskStatusResponseDTO> userStatus(UserRiskStatusRequestDTO requestDTO);
}
