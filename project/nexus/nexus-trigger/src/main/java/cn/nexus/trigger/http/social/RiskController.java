package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRiskApi;
import cn.nexus.api.social.risk.dto.*;
import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 风控接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/risk")
public class RiskController implements IRiskApi {

    @Resource
    private IRiskService riskService;

    @PostMapping("/scan/text")
    @Override
    public Response<TextScanResponseDTO> textScan(@RequestBody TextScanRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        TextScanResultVO vo = riskService.textScan(requestDTO.getContent(), userId, requestDTO.getScenario());
        TextScanResponseDTO dto = TextScanResponseDTO.builder().result(vo.getResult()).tags(vo.getTags()).build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @PostMapping("/scan/image")
    @Override
    public Response<ImageScanResponseDTO> imageScan(@RequestBody ImageScanRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        ImageScanResultVO vo = riskService.imageScan(requestDTO.getImageUrl(), userId);
        ImageScanResponseDTO dto = ImageScanResponseDTO.builder().taskId(vo.getTaskId()).build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @GetMapping("/user/status")
    @Override
    public Response<UserRiskStatusResponseDTO> userStatus(UserRiskStatusRequestDTO requestDTO) {
        Long userId = UserContext.requireUserId();
        UserRiskStatusVO vo = riskService.userStatus(userId);
        UserRiskStatusResponseDTO dto = UserRiskStatusResponseDTO.builder()
                .status(vo.getStatus())
                .capabilities(vo.getCapabilities())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
