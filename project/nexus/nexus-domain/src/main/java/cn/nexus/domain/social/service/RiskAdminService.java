package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskCaseRepository;
import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPromptVersionRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.adapter.repository.IRiskRuleVersionRepository;
import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.RiskRuleSetVO;
import cn.nexus.domain.social.model.valobj.RiskSignalVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 风控后台实现：规则版本、Prompt 版本、人审工单、处罚、审计查询。
 *
 * <p>面向运营/审核后台，负责版本发布与回滚、人工工单的分配与裁决，以及裁决结果回写业务侧（内容与评论）。</p>
 *
 * @author {$authorName}
 * @since 2026-01-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAdminService implements IRiskAdminService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    private static final String CASE_STATUS_OPEN = "OPEN";
    private static final String CASE_STATUS_ASSIGNED = "ASSIGNED";

    private static final String PUNISH_STATUS_ACTIVE = "ACTIVE";

    private static final String USER_STATUS_CACHE_PREFIX = "risk:status:";

    private final ISocialIdPort socialIdPort;
    private final IRiskRuleVersionRepository ruleVersionRepository;
    private final IRiskPromptVersionRepository promptVersionRepository;
    private final IRiskCaseRepository caseRepository;
    private final IRiskPunishmentRepository punishmentRepository;
    private final IRiskDecisionLogRepository decisionLogRepository;
    private final IContentService contentService;
    private final IInteractionService interactionService;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建或更新规则版本（仅允许 DRAFT 状态更新）。
     *
     * <p>version 为空时创建新版本；不为空时更新指定版本的 rulesJson。</p>
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param version 规则版本号（为空则创建新版本） {@link Long}
     * @param rulesJson 规则集合 JSON {@link String}
     * @return 保存后的规则版本 {@link RiskRuleVersionEntity}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskRuleVersionEntity upsertRuleVersion(Long operatorId, Long version, String rulesJson) {
        requireId(operatorId, "operatorId");
        requireNonBlank(rulesJson, "rulesJson");
        validateRulesJson(rulesJson);

        if (version == null) {
            long newVersion = nextVersion();
            long now = socialIdPort.now();
            RiskRuleVersionEntity entity = RiskRuleVersionEntity.builder()
                    .version(newVersion)
                    .status(STATUS_DRAFT)
                    .rulesJson(rulesJson)
                    .createBy(operatorId)
                    .publishBy(null)
                    .publishTime(null)
                    .createTime(now)
                    .updateTime(now)
                    .build();
            boolean ok = ruleVersionRepository.insert(entity);
            if (!ok) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(), "创建规则版本失败");
            }
            RiskRuleVersionEntity saved = ruleVersionRepository.findByVersion(newVersion);
            return saved == null ? entity : saved;
        }

        RiskRuleVersionEntity existed = ruleVersionRepository.findByVersion(version);
        if (existed == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则版本不存在");
        }
        if (!STATUS_DRAFT.equalsIgnoreCase(existed.getStatus())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "仅允许更新 DRAFT 版本");
        }
        boolean updated = ruleVersionRepository.updateRulesJson(version, rulesJson, STATUS_DRAFT);
        if (!updated) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "更新规则版本失败");
        }
        RiskRuleVersionEntity saved = ruleVersionRepository.findByVersion(version);
        if (saved == null) {
            existed.setRulesJson(rulesJson);
            return existed;
        }
        return saved;
    }

    /**
     * 获取当前生效的规则版本。
     *
     * @return 生效版本（可能为空） {@link RiskRuleVersionEntity}
     */
    @Override
    public RiskRuleVersionEntity activeRuleVersion() {
        return ruleVersionRepository.findActive();
    }

    /**
     * 列出所有规则版本（含未发布/已回滚）。
     *
     * @return 规则版本列表 {@link List}
     */
    @Override
    public List<RiskRuleVersionEntity> listRuleVersions() {
        return ruleVersionRepository.listAll();
    }

    /**
     * 发布规则版本：可在发布前对规则集打补丁（shadow/canary 参数），然后把版本状态置为 PUBLISHED。
     *
     * <p>补丁会被写回 rulesJson，目的是让“发布的内容”与“回滚/审计的内容”保持一致。</p>
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param version 规则版本号 {@link Long}
     * @param shadow 是否影子模式（可为空，不传则保持原值） {@link Boolean}
     * @param canaryPercent 灰度百分比（可为空，不传则保持原值） {@link Integer}
     * @param canarySalt 灰度盐值（可为空，不传则保持原值） {@link String}
     * @return 发布结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO publishRuleVersion(Long operatorId, Long version, Boolean shadow, Integer canaryPercent, String canarySalt) {
        requireId(operatorId, "operatorId");
        requireId(version, "version");
        RiskRuleVersionEntity existed = ruleVersionRepository.findByVersion(version);
        if (existed == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "规则版本不存在");
        }
        if (STATUS_PUBLISHED.equalsIgnoreCase(existed.getStatus())) {
            return OperationResultVO.builder().success(true).id(version).status(STATUS_PUBLISHED).message("已发布").build();
        }
        if (existed.getRulesJson() == null || existed.getRulesJson().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "rules_json 不能为空");
        }

        // 发布时把 shadow/canary 等参数固化到版本内容，确保“当前生效版本”可被完整审计与回放。
        String patched = patchRuleSet(existed.getRulesJson(), shadow, canaryPercent, canarySalt);
        if (!patched.equals(existed.getRulesJson())) {
            // 仅在未发布状态下允许更新 rules_json
            String expectedStatus = existed.getStatus() == null ? "" : existed.getStatus();
            boolean ok = ruleVersionRepository.updateRulesJson(version, patched, expectedStatus);
            if (!ok) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(), "发布前更新 rules_json 失败");
            }
        }

        boolean ok = ruleVersionRepository.publish(version, operatorId);
        return OperationResultVO.builder()
                .success(ok)
                .id(version)
                .status(ok ? STATUS_PUBLISHED : "FAILED")
                .message(ok ? "OK" : "发布失败")
                .build();
    }

    /**
     * 回滚规则版本：把指定版本（或自动选择的目标版本）回滚为新的生效版本。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param toVersion 目标版本号（可为空，空则自动选择） {@link Long}
     * @return 回滚结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO rollbackRuleVersion(Long operatorId, Long toVersion) {
        requireId(operatorId, "operatorId");
        Long target = toVersion == null ? chooseRollbackTarget() : toVersion;
        if (target == null) {
            return OperationResultVO.builder().success(false).status("FAILED").message("没有可回滚版本").build();
        }
        boolean ok = ruleVersionRepository.rollback(target, operatorId);
        return OperationResultVO.builder()
                .success(ok)
                .id(target)
                .status(ok ? STATUS_PUBLISHED : "FAILED")
                .message(ok ? "OK" : "回滚失败")
                .build();
    }

    /**
     * 创建或更新 Prompt 版本（仅允许 DRAFT 状态更新）。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param version Prompt 版本号（为空则创建新版本） {@link Long}
     * @param contentType 内容类型（TEXT/IMAGE） {@link String}
     * @param promptText Prompt 文本 {@link String}
     * @param model 模型标识（可为空） {@link String}
     * @return 保存后的 Prompt 版本 {@link RiskPromptVersionEntity}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskPromptVersionEntity upsertPromptVersion(Long operatorId, Long version, String contentType, String promptText, String model) {
        requireId(operatorId, "operatorId");
        String ct = normalizePromptContentType(contentType);
        requireNonBlank(promptText, "promptText");

        if (version == null) {
            long newVersion = nextPromptVersion();
            long now = socialIdPort.now();
            RiskPromptVersionEntity entity = RiskPromptVersionEntity.builder()
                    .version(newVersion)
                    .contentType(ct)
                    .status(STATUS_DRAFT)
                    .promptText(promptText)
                    .model(model == null ? "" : model)
                    .createBy(operatorId)
                    .publishBy(null)
                    .publishTime(null)
                    .createTime(now)
                    .updateTime(now)
                    .build();
            boolean ok = promptVersionRepository.insert(entity);
            if (!ok) {
                throw new AppException(ResponseCode.UN_ERROR.getCode(), "创建 Prompt 版本失败");
            }
            RiskPromptVersionEntity saved = promptVersionRepository.findByVersion(newVersion);
            return saved == null ? entity : saved;
        }

        RiskPromptVersionEntity existed = promptVersionRepository.findByVersion(version);
        if (existed == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "Prompt 版本不存在");
        }
        if (!STATUS_DRAFT.equalsIgnoreCase(existed.getStatus())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "仅允许更新 DRAFT 版本");
        }
        if (existed.getContentType() != null && !ct.equalsIgnoreCase(existed.getContentType())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "contentType 不允许变更");
        }
        boolean updated = promptVersionRepository.updatePrompt(version, promptText, model == null ? "" : model, STATUS_DRAFT);
        if (!updated) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "更新 Prompt 版本失败");
        }
        RiskPromptVersionEntity saved = promptVersionRepository.findByVersion(version);
        if (saved == null) {
            existed.setPromptText(promptText);
            existed.setModel(model == null ? "" : model);
            return existed;
        }
        return saved;
    }

    /**
     * 获取指定内容类型的生效 Prompt 版本。
     *
     * @param contentType 内容类型（TEXT/IMAGE） {@link String}
     * @return 生效 Prompt 版本（可能为空） {@link RiskPromptVersionEntity}
     */
    @Override
    public RiskPromptVersionEntity activePromptVersion(String contentType) {
        String ct = normalizePromptContentType(contentType);
        return promptVersionRepository.findActive(ct);
    }

    /**
     * 列出 Prompt 版本：contentType 为空则返回全部，否则按内容类型过滤。
     *
     * @param contentType 内容类型（可为空） {@link String}
     * @return Prompt 版本列表 {@link List}
     */
    @Override
    public List<RiskPromptVersionEntity> listPromptVersions(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return promptVersionRepository.listAll(null);
        }
        String ct = normalizePromptContentType(contentType);
        return promptVersionRepository.listAll(ct);
    }

    /**
     * 发布 Prompt 版本：将版本状态置为 PUBLISHED。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param version Prompt 版本号 {@link Long}
     * @return 发布结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO publishPromptVersion(Long operatorId, Long version) {
        requireId(operatorId, "operatorId");
        requireId(version, "version");
        RiskPromptVersionEntity existed = promptVersionRepository.findByVersion(version);
        if (existed == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "Prompt 版本不存在");
        }
        if (STATUS_PUBLISHED.equalsIgnoreCase(existed.getStatus())) {
            return OperationResultVO.builder().success(true).id(version).status(STATUS_PUBLISHED).message("已发布").build();
        }
        if (existed.getPromptText() == null || existed.getPromptText().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "promptText 不能为空");
        }
        boolean ok = promptVersionRepository.publish(version, operatorId);
        return OperationResultVO.builder()
                .success(ok)
                .id(version)
                .status(ok ? STATUS_PUBLISHED : "FAILED")
                .message(ok ? "OK" : "发布失败")
                .build();
    }

    /**
     * 回滚 Prompt 版本：把指定版本（或自动选择的目标版本）回滚为新的生效版本。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param toVersion 目标版本号（可为空，空则自动选择） {@link Long}
     * @return 回滚结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO rollbackPromptVersion(Long operatorId, Long toVersion) {
        requireId(operatorId, "operatorId");
        Long target = toVersion == null ? choosePromptRollbackTarget() : toVersion;
        if (target == null) {
            return OperationResultVO.builder().success(false).status("FAILED").message("没有可回滚版本").build();
        }
        boolean ok = promptVersionRepository.rollback(target, operatorId);
        return OperationResultVO.builder()
                .success(ok)
                .id(target)
                .status(ok ? STATUS_PUBLISHED : "FAILED")
                .message(ok ? "OK" : "回滚失败")
                .build();
    }

    /**
     * 查询工单列表（按状态/队列/时间范围过滤）。
     *
     * @param status 工单状态（可为空） {@link String}
     * @param queue 队列/分组（可为空） {@link String}
     * @param beginTimeMs 开始时间（毫秒，可为空） {@link Long}
     * @param endTimeMs 结束时间（毫秒，可为空） {@link Long}
     * @param limit 分页大小（可为空） {@link Integer}
     * @param offset 分页偏移（可为空） {@link Integer}
     * @return 工单列表 {@link List}
     */
    @Override
    public List<RiskCaseEntity> listCases(String status, String queue, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset) {
        return caseRepository.list(status, queue, beginTimeMs, endTimeMs, limit, offset);
    }

    /**
     * 分配工单：使用 expectedStatus 做乐观锁，避免重复分配或状态漂移。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param caseId 工单 ID {@link Long}
     * @param assignee 指派给谁（用户 ID） {@link Long}
     * @param expectedStatus 期望当前状态（可为空，默认 OPEN） {@link String}
     * @return 分配结果 {@link OperationResultVO}
     */
    @Override
    public OperationResultVO assignCase(Long operatorId, Long caseId, Long assignee, String expectedStatus) {
        requireId(operatorId, "operatorId");
        requireId(caseId, "caseId");
        requireId(assignee, "assignee");
        String expected = expectedStatus == null || expectedStatus.isBlank() ? CASE_STATUS_OPEN : expectedStatus;
        boolean ok = caseRepository.assign(caseId, assignee, expected);
        return OperationResultVO.builder()
                .success(ok)
                .id(caseId)
                .status(ok ? CASE_STATUS_ASSIGNED : "FAILED")
                .message(ok ? "OK" : "分配失败（可能状态已变化）")
                .build();
    }

    /**
     * 裁决工单：先将工单从 ASSIGNED 置为完成，再把裁决结果写入 decision_log 并回写业务侧。
     *
     * <p>以工单状态作为“是否已处理”的幂等开关，避免重复提交导致重复处罚或重复回写。</p>
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param caseId 工单 ID {@link Long}
     * @param result 最终结论（PASS/BLOCK） {@link String}
     * @param reasonCode 原因码（可为空） {@link String}
     * @param evidenceJson 证据 JSON（可为空） {@link String}
     * @param expectedStatus 期望当前状态（可为空，默认 ASSIGNED） {@link String}
     * @param punishType 处罚类型（可为空，BLOCK 时可选） {@link String}
     * @param punishDurationSeconds 处罚时长（秒，可为空，BLOCK 时可选） {@link Long}
     * @return 裁决结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO decideCase(Long operatorId,
                                       Long caseId,
                                       String result,
                                       String reasonCode,
                                       String evidenceJson,
                                       String expectedStatus,
                                       String punishType,
                                       Long punishDurationSeconds) {
        requireId(operatorId, "operatorId");
        requireId(caseId, "caseId");
        String r = normalizeFinalResult(result);
        String expected = expectedStatus == null || expectedStatus.isBlank() ? CASE_STATUS_ASSIGNED : expectedStatus;

        RiskCaseEntity c = caseRepository.findByCaseId(caseId);
        if (c == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工单不存在");
        }
        // 用 expectedStatus 做乐观锁：确保工单只会被裁决一次，避免重复提交导致重复处罚/重复回写。
        boolean finished = caseRepository.finish(caseId, r, evidenceJson, expected);
        if (!finished) {
            return OperationResultVO.builder().success(false).id(caseId).status("FAILED").message("提交失败（可能状态已变化）").build();
        }

        // 工单完成后再推进 decision_log 与业务侧状态，保证“先落库，再对外可见”的一致性。
        applyCaseDecision(operatorId, c.getDecisionId(), r, reasonCode, evidenceJson, punishType, punishDurationSeconds);
        return OperationResultVO.builder().success(true).id(caseId).status("DONE").message("OK").build();
    }

    /**
     * 对用户施加处罚：写入处罚记录，并在需要时以（decisionId + type）做幂等去重。
     *
     * <p>decisionId 为空：视为后台手工操作，允许重复写入；decisionId 不为空：使用 insertIgnore 保证幂等。</p>
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param userId 用户 ID {@link Long}
     * @param type 处罚类型 {@link String}
     * @param decisionId 决策日志 ID（可为空） {@link Long}
     * @param reasonCode 原因码（可为空） {@link String}
     * @param startTimeMs 开始时间（毫秒，可为空，默认当前时间） {@link Long}
     * @param endTimeMs 结束时间（毫秒，可为空） {@link Long}
     * @param durationSeconds 时长（秒，可为空，用于计算 endTime） {@link Long}
     * @return 处罚结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO applyPunishment(Long operatorId,
                                            Long userId,
                                            String type,
                                            Long decisionId,
                                            String reasonCode,
                                            Long startTimeMs,
                                            Long endTimeMs,
                                            Long durationSeconds) {
        requireId(operatorId, "operatorId");
        requireId(userId, "userId");
        requireNonBlank(type, "type");

        // endTimeMs 优先，其次使用 durationSeconds 计算；避免后台误操作写入“无效处罚”（end <= start）。
        long start = startTimeMs == null ? socialIdPort.now() : startTimeMs;
        Long end = endTimeMs;
        if (end == null && durationSeconds != null && durationSeconds > 0) {
            end = start + durationSeconds * 1000L;
        }
        if (end == null || end <= start) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "endTime/durationSeconds 非法");
        }

        RiskPunishmentEntity entity = RiskPunishmentEntity.builder()
                .punishId(socialIdPort.nextId())
                .userId(userId)
                .type(type)
                .status(PUNISH_STATUS_ACTIVE)
                .startTime(start)
                .endTime(end)
                .reasonCode(reasonCode == null ? "" : reasonCode)
                .decisionId(decisionId)
                .operatorId(operatorId)
                .createTime(socialIdPort.now())
                .updateTime(socialIdPort.now())
                .build();

        // decisionId 为空表示“后台手工”处罚；非空表示来自决策链路，需用（decisionId + type）做幂等。
        boolean inserted = decisionId == null ? punishmentRepository.insert(entity) : punishmentRepository.insertIgnore(entity);
        if (!inserted && decisionId != null) {
            // 幂等命中：视为成功，并返回已存在的处罚 ID（若能查到）。
            invalidateUserStatusCache(userId);
            RiskPunishmentEntity existed = punishmentRepository.findByDecisionAndType(decisionId, type);
            Long id = existed == null ? null : existed.getPunishId();
            return OperationResultVO.builder().success(true).id(id).status("DUPLICATE").message("重复请求已忽略").build();
        }
        if (!inserted) {
            return OperationResultVO.builder().success(false).id(entity.getPunishId()).status("FAILED").message("处罚写入失败").build();
        }
        invalidateUserStatusCache(userId);
        return OperationResultVO.builder().success(true).id(entity.getPunishId()).status(PUNISH_STATUS_ACTIVE).message("OK").build();
    }

    /**
     * 撤销处罚：将处罚标记为已撤销。
     *
     * @param operatorId 操作人 ID {@link Long}
     * @param punishId 处罚 ID {@link Long}
     * @return 撤销结果 {@link OperationResultVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO revokePunishment(Long operatorId, Long punishId) {
        requireId(operatorId, "operatorId");
        requireId(punishId, "punishId");
        boolean ok = punishmentRepository.revoke(punishId, operatorId);
        return OperationResultVO.builder()
                .success(ok)
                .id(punishId)
                .status(ok ? "REVOKED" : "FAILED")
                .message(ok ? "OK" : "撤销失败（可能已撤销/已过期）")
                .build();
    }

    /**
     * 查询处罚列表（按用户、类型、时间范围过滤）。
     *
     * @param userId 用户 ID（可为空） {@link Long}
     * @param type 处罚类型（可为空） {@link String}
     * @param beginTimeMs 开始时间（毫秒，可为空） {@link Long}
     * @param endTimeMs 结束时间（毫秒，可为空） {@link Long}
     * @param limit 分页大小（可为空） {@link Integer}
     * @param offset 分页偏移（可为空） {@link Integer}
     * @return 处罚列表 {@link List}
     */
    @Override
    public List<RiskPunishmentEntity> listPunishments(Long userId, String type, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset) {
        return punishmentRepository.listByFilter(userId, type, beginTimeMs, endTimeMs, limit, offset);
    }

    /**
     * 查询决策日志列表（按用户、动作、场景、结果、时间范围过滤）。
     *
     * @param userId 用户 ID（可为空） {@link Long}
     * @param actionType 动作类型（可为空） {@link String}
     * @param scenario 场景（可为空） {@link String}
     * @param result 结果（可为空） {@link String}
     * @param beginTimeMs 开始时间（毫秒，可为空） {@link Long}
     * @param endTimeMs 结束时间（毫秒，可为空） {@link Long}
     * @param limit 分页大小（可为空） {@link Integer}
     * @param offset 分页偏移（可为空） {@link Integer}
     * @return 决策日志列表 {@link List}
     */
    @Override
    public List<RiskDecisionLogEntity> listDecisions(Long userId,
                                                    String actionType,
                                                    String scenario,
                                                    String result,
                                                    Long beginTimeMs,
                                                    Long endTimeMs,
                                                    Integer limit,
                                                    Integer offset) {
        return decisionLogRepository.listByFilter(userId, actionType, scenario, result, beginTimeMs, endTimeMs, limit, offset);
    }

    private void applyCaseDecision(Long operatorId,
                                   Long decisionId,
                                   String finalResult,
                                   String reasonCode,
                                   String evidenceJson,
                                   String punishType,
                                   Long punishDurationSeconds) {
        if (decisionId == null) {
            return;
        }
        RiskDecisionLogEntity logEntity = decisionLogRepository.findByDecisionId(decisionId);
        if (logEntity == null) {
            return;
        }
        String rc = (reasonCode == null || reasonCode.isBlank())
                ? (logEntity.getReasonCode() == null ? "MANUAL_REVIEW" : logEntity.getReasonCode())
                : reasonCode;
        String signalsJson = appendReviewSignal(logEntity.getSignalsJson(), finalResult, evidenceJson);
        decisionLogRepository.updateResult(decisionId, finalResult, rc, signalsJson, logEntity.getActionsJson(), logEntity.getExtJson());

        applyToBiz(logEntity, finalResult, rc);
        applyPunishIfNeeded(operatorId, logEntity, finalResult, rc, punishType, punishDurationSeconds);
    }

    private void applyToBiz(RiskDecisionLogEntity logEntity, String result, String reasonCode) {
        if (logEntity == null || result == null || result.isBlank()) {
            return;
        }
        String actionType = logEntity.getActionType();
        if (actionType == null || actionType.isBlank()) {
            return;
        }
        if ("PUBLISH_POST".equalsIgnoreCase(actionType) || "EDIT_POST".equalsIgnoreCase(actionType)) {
            Long attemptId = readLongFromExt(logEntity.getExtJson(), "attemptId");
            if (attemptId != null) {
                contentService.applyRiskReviewResult(attemptId, result, reasonCode);
            }
            return;
        }
        if ("COMMENT_CREATE".equalsIgnoreCase(actionType)) {
            Long commentId = readLongFromExt(logEntity.getExtJson(), "commentId");
            if (commentId != null) {
                interactionService.applyCommentRiskReviewResult(commentId, result, reasonCode);
            }
        }
    }

    private void applyPunishIfNeeded(Long operatorId,
                                    RiskDecisionLogEntity logEntity,
                                    String result,
                                    String reasonCode,
                                    String punishType,
                                    Long punishDurationSeconds) {
        if (logEntity == null || operatorId == null) {
            return;
        }
        if (!"BLOCK".equalsIgnoreCase(result)) {
            return;
        }
        if (punishType == null || punishType.isBlank()) {
            return;
        }
        if (punishDurationSeconds == null || punishDurationSeconds <= 0) {
            return;
        }
        long now = socialIdPort.now();
        RiskPunishmentEntity entity = RiskPunishmentEntity.builder()
                .punishId(socialIdPort.nextId())
                .userId(logEntity.getUserId())
                .type(punishType.trim())
                .status(PUNISH_STATUS_ACTIVE)
                .startTime(now)
                .endTime(now + punishDurationSeconds * 1000L)
                .reasonCode(reasonCode == null ? "" : reasonCode)
                .decisionId(logEntity.getDecisionId())
                .operatorId(operatorId)
                .createTime(now)
                .updateTime(now)
                .build();
        punishmentRepository.insertIgnore(entity);
        invalidateUserStatusCache(logEntity.getUserId());
    }

    private String appendReviewSignal(String existingSignalsJson, String result, String evidenceJson) {
        List<RiskSignalVO> signals = parseSignals(existingSignalsJson);
        signals.add(RiskSignalVO.builder()
                .source("REVIEW")
                .name("MANUAL_REVIEW")
                .score(1D)
                .tags(List.of(result == null ? "" : result))
                .detailJson(evidenceJson)
                .build());
        return toJsonSafe(signals);
    }

    private List<RiskSignalVO> parseSignals(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<RiskSignalVO> list = objectMapper.readValue(json, new TypeReference<List<RiskSignalVO>>() {
            });
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Long readLongFromExt(String extJson, String field) {
        if (extJson == null || extJson.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(extJson, Map.class);
            Object v = map == null ? null : map.get(field);
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                return n.longValue();
            }
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private void invalidateUserStatusCache(Long userId) {
        if (userId == null || redissonClient == null) {
            return;
        }
        try {
            RBucket<Object> bucket = redissonClient.getBucket(USER_STATUS_CACHE_PREFIX + userId);
            bucket.delete();
        } catch (Exception ignored) {
        }
    }

    private long nextVersion() {
        Long max = ruleVersionRepository.maxVersion();
        if (max == null || max < 0) {
            return 1L;
        }
        return max + 1L;
    }

    private long nextPromptVersion() {
        Long max = promptVersionRepository.maxVersion();
        if (max == null || max < 0) {
            return 1L;
        }
        return max + 1L;
    }

    private Long choosePromptRollbackTarget() {
        List<RiskPromptVersionEntity> all = promptVersionRepository.listAll(null);
        if (all == null || all.isEmpty()) {
            return null;
        }
        return all.stream()
                .filter(v -> v != null && v.getVersion() != null)
                .filter(v -> STATUS_ROLLED_BACK.equalsIgnoreCase(v.getStatus()))
                .max(Comparator.comparingLong((RiskPromptVersionEntity e) -> e.getPublishTime() == null ? 0L : e.getPublishTime())
                        .thenComparingLong(e -> e.getVersion() == null ? 0L : e.getVersion()))
                .map(RiskPromptVersionEntity::getVersion)
                .orElse(null);
    }

    private String normalizePromptContentType(String raw) {
        requireNonBlank(raw, "contentType");
        String ct = raw.trim().toUpperCase();
        if ("TEXT".equals(ct) || "IMAGE".equals(ct)) {
            return ct;
        }
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "contentType 仅支持 TEXT/IMAGE");
    }

    private Long chooseRollbackTarget() {
        RiskRuleVersionEntity active = ruleVersionRepository.findActive();
        Long activeV = active == null ? null : active.getVersion();
        List<RiskRuleVersionEntity> all = ruleVersionRepository.listAll();
        if (all == null || all.isEmpty()) {
            return null;
        }
        return all.stream()
                .filter(v -> v != null && v.getVersion() != null && (activeV == null || !v.getVersion().equals(activeV)))
                .filter(v -> STATUS_ROLLED_BACK.equalsIgnoreCase(v.getStatus()))
                .max(Comparator.comparingLong((RiskRuleVersionEntity e) -> e.getPublishTime() == null ? 0L : e.getPublishTime())
                        .thenComparingLong(e -> e.getVersion() == null ? 0L : e.getVersion()))
                .map(RiskRuleVersionEntity::getVersion)
                .orElse(null);
    }

    private void validateRulesJson(String rulesJson) {
        try {
            RiskRuleSetVO set = objectMapper.readValue(rulesJson, RiskRuleSetVO.class);
            if (set == null) {
                throw new IllegalStateException("rules_json 为空");
            }
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "rules_json 解析失败");
        }
    }

    private String patchRuleSet(String rulesJson, Boolean shadow, Integer canaryPercent, String canarySalt) {
        boolean needPatch = shadow != null || canaryPercent != null || (canarySalt != null && !canarySalt.isBlank());
        if (!needPatch) {
            validateRulesJson(rulesJson);
            return rulesJson;
        }
        try {
            RiskRuleSetVO set = objectMapper.readValue(rulesJson, RiskRuleSetVO.class);
            if (set == null) {
                throw new IllegalStateException("rules_json 为空");
            }
            if (shadow != null) {
                set.setShadow(shadow);
            }
            if (canaryPercent != null) {
                set.setCanaryPercent(canaryPercent);
            }
            if (canarySalt != null && !canarySalt.isBlank()) {
                set.setCanarySalt(canarySalt);
            }
            return objectMapper.writeValueAsString(set);
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "rules_json patch 失败");
        }
    }

    private String normalizeFinalResult(String raw) {
        requireNonBlank(raw, "result");
        String r = raw.trim().toUpperCase();
        if ("PASS".equals(r) || "BLOCK".equals(r)) {
            return r;
        }
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "result 仅支持 PASS/BLOCK");
    }

    private void requireId(Long id, String name) {
        if (id == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), name + " 不能为空");
        }
    }

    private void requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), name + " 不能为空");
        }
    }

    private String toJsonSafe(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.debug("toJson failed", e);
            return null;
        }
    }
}
