package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.risk.admin.dto.*;

/**
 * 风控后台（risk-admin）接口定义：用于规则管理、人审工单、处罚、审计与申诉处理。
 */
public interface IRiskAdminApi {

    /** 创建/更新规则版本（rules_json） */
    Response<RiskRuleVersionUpsertResponseDTO> upsertRuleVersion(RiskRuleVersionUpsertRequestDTO requestDTO);

    /** 查询规则版本列表（包含当前生效版本） */
    Response<RiskRuleVersionListResponseDTO> listRuleVersions(RiskRuleVersionListRequestDTO requestDTO);

    /** 发布规则版本 */
    Response<OperationResultDTO> publishRuleVersion(Long version, RiskRuleVersionPublishRequestDTO requestDTO);

    /** 回滚到指定/上一个稳定版本 */
    Response<OperationResultDTO> rollbackRuleVersion(RiskRuleVersionRollbackRequestDTO requestDTO);

    /** 创建/更新 Prompt 版本（prompt_text） */
    Response<RiskPromptVersionUpsertResponseDTO> upsertPromptVersion(RiskPromptVersionUpsertRequestDTO requestDTO);

    /** 查询 Prompt 版本列表（包含当前生效版本） */
    Response<RiskPromptVersionListResponseDTO> listPromptVersions(RiskPromptVersionListRequestDTO requestDTO);

    /** 发布 Prompt 版本 */
    Response<OperationResultDTO> publishPromptVersion(Long version, RiskPromptVersionPublishRequestDTO requestDTO);

    /** 回滚 Prompt 版本（到指定/上一个稳定版本） */
    Response<OperationResultDTO> rollbackPromptVersion(RiskPromptVersionRollbackRequestDTO requestDTO);

    /** 查询人审工单 */
    Response<RiskCaseListResponseDTO> listCases(RiskCaseListRequestDTO requestDTO);

    /** 领取/分配工单 */
    Response<OperationResultDTO> assignCase(Long caseId, RiskCaseAssignRequestDTO requestDTO);

    /** 提交工单结论 */
    Response<OperationResultDTO> decideCase(Long caseId, RiskCaseDecisionRequestDTO requestDTO);

    /** 施加处罚 */
    Response<OperationResultDTO> applyPunishment(RiskPunishmentApplyRequestDTO requestDTO);

    /** 撤销处罚 */
    Response<OperationResultDTO> revokePunishment(RiskPunishmentRevokeRequestDTO requestDTO);

    /** 查询处罚 */
    Response<RiskPunishmentListResponseDTO> listPunishments(RiskPunishmentListRequestDTO requestDTO);

    /** 查询决策审计日志 */
    Response<RiskDecisionLogListResponseDTO> listDecisions(RiskDecisionLogListRequestDTO requestDTO);

    /** 处理申诉 */
    Response<OperationResultDTO> decideAppeal(Long appealId, RiskAppealDecisionRequestDTO requestDTO);
}
