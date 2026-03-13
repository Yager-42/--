package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;

import java.util.List;

/**
 * 风控后台服务：规则版本、人审工单、处罚、审计查询等运营能力。
 */
public interface IRiskAdminService {

    RiskRuleVersionEntity upsertRuleVersion(Long operatorId, Long version, String rulesJson);

    RiskRuleVersionEntity activeRuleVersion();

    List<RiskRuleVersionEntity> listRuleVersions();

    OperationResultVO publishRuleVersion(Long operatorId, Long version, Boolean shadow, Integer canaryPercent, String canarySalt);

    OperationResultVO rollbackRuleVersion(Long operatorId, Long toVersion);

    /** 创建/更新 Prompt 版本（prompt_text） */
    RiskPromptVersionEntity upsertPromptVersion(Long operatorId, Long version, String contentType, String promptText, String model);

    /** 查询指定类型当前生效 Prompt（PUBLISHED） */
    RiskPromptVersionEntity activePromptVersion(String contentType);

    /** 查询 Prompt 版本列表 */
    List<RiskPromptVersionEntity> listPromptVersions(String contentType);

    /** 发布 Prompt 版本 */
    OperationResultVO publishPromptVersion(Long operatorId, Long version);

    /** 回滚 Prompt 版本（到指定/上一个稳定版本） */
    OperationResultVO rollbackPromptVersion(Long operatorId, Long toVersion);

    List<RiskCaseEntity> listCases(String status, String queue, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset);

    OperationResultVO assignCase(Long operatorId, Long caseId, Long assignee, String expectedStatus);

    OperationResultVO decideCase(Long operatorId,
                                Long caseId,
                                String result,
                                String reasonCode,
                                String evidenceJson,
                                String expectedStatus,
                                String punishType,
                                Long punishDurationSeconds);

    OperationResultVO applyPunishment(Long operatorId,
                                     Long userId,
                                     String type,
                                     Long decisionId,
                                     String reasonCode,
                                     Long startTimeMs,
                                     Long endTimeMs,
                                     Long durationSeconds);

    OperationResultVO revokePunishment(Long operatorId, Long punishId);

    List<RiskPunishmentEntity> listPunishments(Long userId, String type, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset);

    List<RiskDecisionLogEntity> listDecisions(Long userId,
                                             String actionType,
                                             String scenario,
                                             String result,
                                             Long beginTimeMs,
                                             Long endTimeMs,
                                             Integer limit,
                                             Integer offset);
}
