package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.model.valobj.RiskSignalVO;
import cn.nexus.domain.social.service.IContentService;
import cn.nexus.domain.social.service.IInteractionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 风控异步回写服务：消费 LLM 与图片扫描结果后回写 decision_log，并生成后续处置所需信息。
 *
 * <p>原则：LLM 永不阻塞在线链路；异步链路要能把“隔离中的内容与评论”推进到最终状态。</p>
 *
 * @author {$authorName}
 * @since 2026-01-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAsyncService {

    private static final String PUNISH_STATUS_ACTIVE = "ACTIVE";

    private final ISocialIdPort socialIdPort;
    private final IRiskDecisionLogRepository decisionLogRepository;
    private final IRiskPunishmentRepository punishmentRepository;
    private final IContentService contentService;
    private final IInteractionService interactionService;
    private final ObjectMapper objectMapper;

    @Value("${risk.autoPunish.enabled:false}")
    private boolean autoPunishEnabled;

    @Value("${risk.autoPunish.minConfidence:0.95}")
    private double autoPunishMinConfidence;

    @Value("${risk.autoPunish.durationSeconds:600}")
    private long autoPunishDurationSeconds;

    /**
     * 应用 LLM 结果：合并 signals、更新 decision_log，并在给出最终结论时推进业务侧状态。
     *
     * <p>该方法是异步链路入口：允许重复投递，内部通过 decisionId 定位并覆盖更新。</p>
     *
     * @param decisionId 决策日志 ID {@link Long}
     * @param llm LLM 结果 {@link RiskLlmResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyLlmResult(Long decisionId, RiskLlmResultVO llm) {
        if (decisionId == null || llm == null) {
            return;
        }
        RiskDecisionLogEntity logEntity = decisionLogRepository.findByDecisionId(decisionId);
        if (logEntity == null) {
            return;
        }
        List<RiskSignalVO> merged = mergeSignals(logEntity.getSignalsJson(), llm);
        String newResult = mapDecisionResult(logEntity.getResult(), llm.getResult());
        String newReason = llm.getReasonCode() == null || llm.getReasonCode().isBlank() ? logEntity.getReasonCode() : llm.getReasonCode();
        decisionLogRepository.updateResult(
                decisionId,
                newResult,
                newReason,
                toJsonSafe(merged),
                logEntity.getActionsJson(),
                logEntity.getExtJson()
        );

        // 若异步结果给出了明确结论，则推进业务侧状态（内容与评论）。
        applyToBiz(logEntity, newResult, newReason);

        // 可选：自动处罚（默认关闭），避免误杀时直接影响用户体验。
        applyAutoPunishIfNeeded(logEntity, llm, newResult, newReason);
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

    private void applyAutoPunishIfNeeded(RiskDecisionLogEntity logEntity, RiskLlmResultVO llm, String finalResult, String reasonCode) {
        if (!autoPunishEnabled) {
            return;
        }
        if (punishmentRepository == null || socialIdPort == null) {
            return;
        }
        if (logEntity == null || llm == null) {
            return;
        }
        if (!"BLOCK".equalsIgnoreCase(llm.getResult())) {
            return;
        }
        if (!"BLOCK".equalsIgnoreCase(finalResult)) {
            return;
        }
        if (autoPunishDurationSeconds <= 0) {
            return;
        }
        Double c = llm.getConfidence();
        if (c == null || c < autoPunishMinConfidence) {
            return;
        }
        Long userId = logEntity.getUserId();
        if (userId == null) {
            return;
        }
        String punishType = mapPunishType(logEntity.getActionType());
        if (punishType == null) {
            return;
        }
        long now = socialIdPort.now();
        long end = now + autoPunishDurationSeconds * 1000L;
        RiskPunishmentEntity entity = RiskPunishmentEntity.builder()
                .punishId(socialIdPort.nextId())
                .userId(userId)
                .type(punishType)
                .status(PUNISH_STATUS_ACTIVE)
                .startTime(now)
                .endTime(end)
                .reasonCode(reasonCode == null ? "" : reasonCode)
                .decisionId(logEntity.getDecisionId())
                .operatorId(0L)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            punishmentRepository.insertIgnore(entity);
        } catch (Exception e) {
            log.debug("auto punish failed, decisionId={}, userId={}, type={}", logEntity.getDecisionId(), userId, punishType, e);
        }
    }

    private String mapPunishType(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return null;
        }
        if ("PUBLISH_POST".equalsIgnoreCase(actionType) || "EDIT_POST".equalsIgnoreCase(actionType)) {
            return "POST_BAN";
        }
        if ("COMMENT_CREATE".equalsIgnoreCase(actionType)) {
            return "COMMENT_BAN";
        }
        if ("DM_SEND".equalsIgnoreCase(actionType)) {
            return "DM_BAN";
        }
        if ("LOGIN".equalsIgnoreCase(actionType) || "REGISTER".equalsIgnoreCase(actionType)) {
            return "LOGIN_BAN";
        }
        return null;
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

    private List<RiskSignalVO> mergeSignals(String existingSignalsJson, RiskLlmResultVO llm) {
        List<RiskSignalVO> signals = parseSignals(existingSignalsJson);
        signals.add(RiskSignalVO.builder()
                .source("LLM")
                .name(llm.getReasonCode() == null ? "LLM" : llm.getReasonCode())
                .score(llm.getConfidence())
                .tags(llm.getRiskTags() == null ? List.of() : llm.getRiskTags())
                .detailJson(toJsonSafe(llm))
                .build());
        return signals;
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

    private String mapDecisionResult(String existed, String llmResult) {
        if (llmResult == null || llmResult.isBlank()) {
            return existed;
        }
        if ("BLOCK".equalsIgnoreCase(llmResult)) {
            return "BLOCK";
        }
        if ("PASS".equalsIgnoreCase(llmResult)) {
            return "PASS";
        }
        if ("REVIEW".equalsIgnoreCase(llmResult)) {
            return "REVIEW";
        }
        return existed;
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
