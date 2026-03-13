package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRiskApi;
import cn.nexus.api.social.risk.dto.*;
import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.RiskActionVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskEventVO;
import cn.nexus.domain.social.model.valobj.RiskSignalVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.social.service.IRiskAppealService;
import cn.nexus.domain.social.service.IRiskService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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

    @Resource
    private IRiskAppealService riskAppealService;

    @PostMapping("/decision")
    @Override
    public Response<RiskDecisionResponseDTO> decision(@RequestBody RiskDecisionRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            if (requestDTO == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "requestDTO 不能为空");
            }
            RiskEventVO event = RiskEventVO.builder()
                    .eventId(requestDTO.getEventId())
                    .userId(userId)
                    .actionType(requestDTO.getActionType())
                    .scenario(requestDTO.getScenario())
                    .contentText(requestDTO.getContentText())
                    .mediaUrls(requestDTO.getMediaUrls())
                    .targetId(requestDTO.getTargetId())
                    .extJson(requestDTO.getExt())
                    .occurTime(System.currentTimeMillis())
                    .build();
            RiskDecisionVO vo = riskService.decision(event);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDecisionDto(vo));
        } catch (AppException e) {
            return Response.<RiskDecisionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk decision api failed, req={}", requestDTO, e);
            return Response.<RiskDecisionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/scan/text")
    @Override
    public Response<TextScanResponseDTO> textScan(@RequestBody TextScanRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            TextScanResultVO vo = riskService.textScan(requestDTO.getContent(), userId, requestDTO.getScenario());
            TextScanResponseDTO dto = TextScanResponseDTO.builder().result(vo.getResult()).tags(vo.getTags()).build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<TextScanResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk textScan api failed, req={}", requestDTO, e);
            return Response.<TextScanResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/scan/image")
    @Override
    public Response<ImageScanResponseDTO> imageScan(@RequestBody ImageScanRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            ImageScanResultVO vo = riskService.imageScan(requestDTO.getImageUrl(), userId);
            ImageScanResponseDTO dto = ImageScanResponseDTO.builder().taskId(vo.getTaskId()).build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<ImageScanResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk imageScan api failed, req={}", requestDTO, e);
            return Response.<ImageScanResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/user/status")
    @Override
    public Response<UserRiskStatusResponseDTO> userStatus(UserRiskStatusRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            UserRiskStatusVO vo = riskService.userStatus(userId);
            UserRiskStatusResponseDTO dto = UserRiskStatusResponseDTO.builder()
                    .status(vo.getStatus())
                    .capabilities(vo.getCapabilities())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<UserRiskStatusResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk userStatus api failed, req={}", requestDTO, e);
            return Response.<UserRiskStatusResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/appeals")
    @Override
    public Response<RiskAppealResponseDTO> appeal(@RequestBody RiskAppealRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = riskAppealService.submitAppeal(
                    userId,
                    requestDTO == null ? null : requestDTO.getDecisionId(),
                    requestDTO == null ? null : requestDTO.getPunishId(),
                    requestDTO == null ? null : requestDTO.getContent());
            RiskAppealResponseDTO dto = RiskAppealResponseDTO.builder()
                    .appealId(vo == null ? null : vo.getId())
                    .status(vo == null ? null : vo.getStatus())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<RiskAppealResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk appeal api failed, req={}", requestDTO, e);
            return Response.<RiskAppealResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private RiskDecisionResponseDTO toDecisionDto(RiskDecisionVO vo) {
        if (vo == null) {
            return null;
        }
        List<RiskActionDTO> actions = new ArrayList<>();
        if (vo.getActions() != null) {
            for (RiskActionVO a : vo.getActions()) {
                if (a == null) {
                    continue;
                }
                actions.add(RiskActionDTO.builder().type(a.getType()).params(a.getParamsJson()).build());
            }
        }
        List<RiskSignalDTO> signals = new ArrayList<>();
        if (vo.getSignals() != null) {
            for (RiskSignalVO s : vo.getSignals()) {
                if (s == null) {
                    continue;
                }
                signals.add(RiskSignalDTO.builder()
                        .source(s.getSource())
                        .name(s.getName())
                        .score(s.getScore())
                        .tags(s.getTags())
                        .build());
            }
        }
        return RiskDecisionResponseDTO.builder()
                .decisionId(vo.getDecisionId())
                .result(vo.getResult())
                .reasonCode(vo.getReasonCode())
                .actions(actions)
                .signals(signals)
                .build();
    }
}
