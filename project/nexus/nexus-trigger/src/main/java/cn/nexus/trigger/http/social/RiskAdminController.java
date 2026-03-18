package cn.nexus.trigger.http.social;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.nexus.api.response.Response;
import cn.nexus.api.social.IRiskAdminApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.risk.admin.dto.*;
import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IRiskAdminService;
import cn.nexus.domain.social.service.IRiskAppealService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控后台接口入口（risk-admin）。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@SaCheckRole("ADMIN")
@RequestMapping("/api/v1/risk/admin")
public class RiskAdminController implements IRiskAdminApi {

    @Resource
    private IRiskAdminService riskAdminService;

    @Resource
    private IRiskAppealService riskAppealService;

    @PostMapping("/rules/versions")
    @Override
    public Response<RiskRuleVersionUpsertResponseDTO> upsertRuleVersion(@RequestBody RiskRuleVersionUpsertRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            RiskRuleVersionEntity saved = riskAdminService.upsertRuleVersion(operatorId, requestDTO == null ? null : requestDTO.getVersion(),
                    requestDTO == null ? null : requestDTO.getRulesJson());
            RiskRuleVersionUpsertResponseDTO dto = RiskRuleVersionUpsertResponseDTO.builder()
                    .version(saved == null ? null : saved.getVersion())
                    .status(saved == null ? null : saved.getStatus())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<RiskRuleVersionUpsertResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin upsertRuleVersion failed, req={}", requestDTO, e);
            return Response.<RiskRuleVersionUpsertResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/rules/versions")
    @Override
    public Response<RiskRuleVersionListResponseDTO> listRuleVersions(RiskRuleVersionListRequestDTO requestDTO) {
        try {
            UserContext.requireUserId();
            boolean includeRulesJson = requestDTO != null && Boolean.TRUE.equals(requestDTO.getIncludeRulesJson());
            RiskRuleVersionEntity active = riskAdminService.activeRuleVersion();
            List<RiskRuleVersionEntity> list = riskAdminService.listRuleVersions();
            List<RiskRuleVersionDTO> versions = new ArrayList<>();
            if (list != null) {
                for (RiskRuleVersionEntity e : list) {
                    if (e == null) {
                        continue;
                    }
                    versions.add(RiskRuleVersionDTO.builder()
                            .version(e.getVersion())
                            .status(e.getStatus())
                            .createBy(e.getCreateBy())
                            .publishBy(e.getPublishBy())
                            .publishTime(e.getPublishTime())
                            .createTime(e.getCreateTime())
                            .updateTime(e.getUpdateTime())
                            .rulesJson(includeRulesJson ? e.getRulesJson() : null)
                            .build());
                }
            }
            RiskRuleVersionListResponseDTO dto = RiskRuleVersionListResponseDTO.builder()
                    .activeVersion(active == null ? null : active.getVersion())
                    .versions(versions)
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<RiskRuleVersionListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin listRuleVersions failed, req={}", requestDTO, e);
            return Response.<RiskRuleVersionListResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/rules/versions/{version}/publish")
    @Override
    public Response<OperationResultDTO> publishRuleVersion(@PathVariable("version") Long version,
                                                          @RequestBody RiskRuleVersionPublishRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.publishRuleVersion(operatorId, version,
                    requestDTO == null ? null : requestDTO.getShadow(),
                    requestDTO == null ? null : requestDTO.getCanaryPercent(),
                    requestDTO == null ? null : requestDTO.getCanarySalt());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin publishRuleVersion failed, version={}, req={}", version, requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/rules/rollback")
    @Override
    public Response<OperationResultDTO> rollbackRuleVersion(@RequestBody RiskRuleVersionRollbackRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.rollbackRuleVersion(operatorId, requestDTO == null ? null : requestDTO.getToVersion());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin rollbackRuleVersion failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/prompts/versions")
    @Override
    public Response<RiskPromptVersionUpsertResponseDTO> upsertPromptVersion(@RequestBody RiskPromptVersionUpsertRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            RiskPromptVersionEntity saved = riskAdminService.upsertPromptVersion(
                    operatorId,
                    requestDTO == null ? null : requestDTO.getVersion(),
                    requestDTO == null ? null : requestDTO.getContentType(),
                    requestDTO == null ? null : requestDTO.getPromptText(),
                    requestDTO == null ? null : requestDTO.getModel());
            RiskPromptVersionUpsertResponseDTO dto = RiskPromptVersionUpsertResponseDTO.builder()
                    .version(saved == null ? null : saved.getVersion())
                    .status(saved == null ? null : saved.getStatus())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<RiskPromptVersionUpsertResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin upsertPromptVersion failed, req={}", requestDTO, e);
            return Response.<RiskPromptVersionUpsertResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/prompts/versions")
    @Override
    public Response<RiskPromptVersionListResponseDTO> listPromptVersions(RiskPromptVersionListRequestDTO requestDTO) {
        try {
            UserContext.requireUserId();
            boolean includePromptText = requestDTO != null && Boolean.TRUE.equals(requestDTO.getIncludePromptText());
            String contentType = requestDTO == null ? null : requestDTO.getContentType();

            Long activeText = null;
            Long activeImage = null;
            if (contentType == null || contentType.isBlank()) {
                RiskPromptVersionEntity at = riskAdminService.activePromptVersion("TEXT");
                RiskPromptVersionEntity ai = riskAdminService.activePromptVersion("IMAGE");
                activeText = at == null ? null : at.getVersion();
                activeImage = ai == null ? null : ai.getVersion();
            } else {
                RiskPromptVersionEntity active = riskAdminService.activePromptVersion(contentType);
                if (active != null && active.getContentType() != null) {
                    if ("IMAGE".equalsIgnoreCase(active.getContentType())) {
                        activeImage = active.getVersion();
                    } else {
                        activeText = active.getVersion();
                    }
                }
            }

            List<RiskPromptVersionEntity> list = riskAdminService.listPromptVersions(contentType);
            List<RiskPromptVersionDTO> versions = new ArrayList<>();
            if (list != null) {
                for (RiskPromptVersionEntity e : list) {
                    if (e == null) {
                        continue;
                    }
                    versions.add(RiskPromptVersionDTO.builder()
                            .version(e.getVersion())
                            .contentType(e.getContentType())
                            .status(e.getStatus())
                            .model(e.getModel())
                            .createBy(e.getCreateBy())
                            .publishBy(e.getPublishBy())
                            .publishTime(e.getPublishTime())
                            .createTime(e.getCreateTime())
                            .updateTime(e.getUpdateTime())
                            .promptText(includePromptText ? e.getPromptText() : null)
                            .build());
                }
            }
            RiskPromptVersionListResponseDTO dto = RiskPromptVersionListResponseDTO.builder()
                    .activeTextVersion(activeText)
                    .activeImageVersion(activeImage)
                    .versions(versions)
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<RiskPromptVersionListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin listPromptVersions failed, req={}", requestDTO, e);
            return Response.<RiskPromptVersionListResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/prompts/versions/{version}/publish")
    @Override
    public Response<OperationResultDTO> publishPromptVersion(@PathVariable("version") Long version,
                                                            @RequestBody RiskPromptVersionPublishRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.publishPromptVersion(operatorId, version);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin publishPromptVersion failed, version={}, req={}", version, requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/prompts/rollback")
    @Override
    public Response<OperationResultDTO> rollbackPromptVersion(@RequestBody RiskPromptVersionRollbackRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.rollbackPromptVersion(operatorId, requestDTO == null ? null : requestDTO.getToVersion());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin rollbackPromptVersion failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/cases")
    @Override
    public Response<RiskCaseListResponseDTO> listCases(RiskCaseListRequestDTO requestDTO) {
        try {
            UserContext.requireUserId();
            List<RiskCaseEntity> list = riskAdminService.listCases(
                    requestDTO == null ? null : requestDTO.getStatus(),
                    requestDTO == null ? null : requestDTO.getQueue(),
                    requestDTO == null ? null : requestDTO.getBeginTime(),
                    requestDTO == null ? null : requestDTO.getEndTime(),
                    requestDTO == null ? null : requestDTO.getLimit(),
                    requestDTO == null ? null : requestDTO.getOffset());
            List<RiskCaseDTO> cases = new ArrayList<>();
            if (list != null) {
                for (RiskCaseEntity e : list) {
                    if (e == null) {
                        continue;
                    }
                    cases.add(RiskCaseDTO.builder()
                            .caseId(e.getCaseId())
                            .decisionId(e.getDecisionId())
                            .status(e.getStatus())
                            .queue(e.getQueue())
                            .assignee(e.getAssignee())
                            .result(e.getResult())
                            .evidenceJson(e.getEvidenceJson())
                            .createTime(e.getCreateTime())
                            .updateTime(e.getUpdateTime())
                            .build());
                }
            }
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    RiskCaseListResponseDTO.builder().cases(cases).build());
        } catch (AppException e) {
            return Response.<RiskCaseListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin listCases failed, req={}", requestDTO, e);
            return Response.<RiskCaseListResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/cases/{caseId}/assign")
    @Override
    public Response<OperationResultDTO> assignCase(@PathVariable("caseId") Long caseId, @RequestBody RiskCaseAssignRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.assignCase(operatorId, caseId,
                    requestDTO == null ? null : requestDTO.getAssignee(),
                    requestDTO == null ? null : requestDTO.getExpectedStatus());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin assignCase failed, caseId={}, req={}", caseId, requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/cases/{caseId}/decision")
    @Override
    public Response<OperationResultDTO> decideCase(@PathVariable("caseId") Long caseId, @RequestBody RiskCaseDecisionRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.decideCase(
                    operatorId,
                    caseId,
                    requestDTO == null ? null : requestDTO.getResult(),
                    requestDTO == null ? null : requestDTO.getReasonCode(),
                    requestDTO == null ? null : requestDTO.getEvidenceJson(),
                    requestDTO == null ? null : requestDTO.getExpectedStatus(),
                    requestDTO == null ? null : requestDTO.getPunishType(),
                    requestDTO == null ? null : requestDTO.getPunishDurationSeconds());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin decideCase failed, caseId={}, req={}", caseId, requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/punishments/apply")
    @Override
    public Response<OperationResultDTO> applyPunishment(@RequestBody RiskPunishmentApplyRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.applyPunishment(operatorId,
                    requestDTO == null ? null : requestDTO.getUserId(),
                    requestDTO == null ? null : requestDTO.getType(),
                    requestDTO == null ? null : requestDTO.getDecisionId(),
                    requestDTO == null ? null : requestDTO.getReasonCode(),
                    requestDTO == null ? null : requestDTO.getStartTime(),
                    requestDTO == null ? null : requestDTO.getEndTime(),
                    requestDTO == null ? null : requestDTO.getDurationSeconds());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin applyPunishment failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/punishments/revoke")
    @Override
    public Response<OperationResultDTO> revokePunishment(@RequestBody RiskPunishmentRevokeRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAdminService.revokePunishment(operatorId, requestDTO == null ? null : requestDTO.getPunishId());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin revokePunishment failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/punishments")
    @Override
    public Response<RiskPunishmentListResponseDTO> listPunishments(RiskPunishmentListRequestDTO requestDTO) {
        try {
            UserContext.requireUserId();
            List<RiskPunishmentEntity> list = riskAdminService.listPunishments(
                    requestDTO == null ? null : requestDTO.getUserId(),
                    requestDTO == null ? null : requestDTO.getType(),
                    requestDTO == null ? null : requestDTO.getBeginTime(),
                    requestDTO == null ? null : requestDTO.getEndTime(),
                    requestDTO == null ? null : requestDTO.getLimit(),
                    requestDTO == null ? null : requestDTO.getOffset());
            List<RiskPunishmentDTO> punishments = new ArrayList<>();
            if (list != null) {
                for (RiskPunishmentEntity e : list) {
                    if (e == null) {
                        continue;
                    }
                    punishments.add(RiskPunishmentDTO.builder()
                            .punishId(e.getPunishId())
                            .userId(e.getUserId())
                            .type(e.getType())
                            .status(e.getStatus())
                            .startTime(e.getStartTime())
                            .endTime(e.getEndTime())
                            .reasonCode(e.getReasonCode())
                            .decisionId(e.getDecisionId())
                            .operatorId(e.getOperatorId())
                            .createTime(e.getCreateTime())
                            .updateTime(e.getUpdateTime())
                            .build());
                }
            }
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    RiskPunishmentListResponseDTO.builder().punishments(punishments).build());
        } catch (AppException e) {
            return Response.<RiskPunishmentListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin listPunishments failed, req={}", requestDTO, e);
            return Response.<RiskPunishmentListResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/decisions")
    @Override
    public Response<RiskDecisionLogListResponseDTO> listDecisions(RiskDecisionLogListRequestDTO requestDTO) {
        try {
            UserContext.requireUserId();
            List<RiskDecisionLogEntity> list = riskAdminService.listDecisions(
                    requestDTO == null ? null : requestDTO.getUserId(),
                    requestDTO == null ? null : requestDTO.getActionType(),
                    requestDTO == null ? null : requestDTO.getScenario(),
                    requestDTO == null ? null : requestDTO.getResult(),
                    requestDTO == null ? null : requestDTO.getBeginTime(),
                    requestDTO == null ? null : requestDTO.getEndTime(),
                    requestDTO == null ? null : requestDTO.getLimit(),
                    requestDTO == null ? null : requestDTO.getOffset());
            List<RiskDecisionLogDTO> decisions = new ArrayList<>();
            if (list != null) {
                for (RiskDecisionLogEntity e : list) {
                    if (e == null) {
                        continue;
                    }
                    decisions.add(RiskDecisionLogDTO.builder()
                            .decisionId(e.getDecisionId())
                            .eventId(e.getEventId())
                            .userId(e.getUserId())
                            .actionType(e.getActionType())
                            .scenario(e.getScenario())
                            .result(e.getResult())
                            .reasonCode(e.getReasonCode())
                            .signalsJson(e.getSignalsJson())
                            .actionsJson(e.getActionsJson())
                            .extJson(e.getExtJson())
                            .traceId(e.getTraceId())
                            .createTime(e.getCreateTime())
                            .updateTime(e.getUpdateTime())
                            .build());
                }
            }
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    RiskDecisionLogListResponseDTO.builder().decisions(decisions).build());
        } catch (AppException e) {
            return Response.<RiskDecisionLogListResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin listDecisions failed, req={}", requestDTO, e);
            return Response.<RiskDecisionLogListResponseDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @PostMapping("/appeals/{appealId}/decision")
    @Override
    public Response<OperationResultDTO> decideAppeal(@PathVariable("appealId") Long appealId, @RequestBody RiskAppealDecisionRequestDTO requestDTO) {
        try {
            Long operatorId = UserContext.requireUserId();
            OperationResultVO vo = riskAppealService.decideAppeal(operatorId, appealId, requestDTO == null ? null : requestDTO.getResult());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toOperationResultDto(vo));
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("risk admin decideAppeal failed, appealId={}, req={}", appealId, requestDTO, e);
            return Response.<OperationResultDTO>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    private OperationResultDTO toOperationResultDto(OperationResultVO vo) {
        if (vo == null) {
            return null;
        }
        return OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
    }
}
