package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IRiskTaskPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskCaseRepository;
import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.adapter.repository.IRiskRuleVersionRepository;
import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.RiskActionVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskEventVO;
import cn.nexus.domain.social.model.valobj.RiskRuleSetVO;
import cn.nexus.domain.social.model.valobj.RiskRuleVO;
import cn.nexus.domain.social.model.valobj.RiskSignalVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;
import cn.nexus.types.exception.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 风控服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService implements IRiskService {

    private final ISocialIdPort socialIdPort;
    private final IRiskDecisionLogRepository decisionLogRepository;
    private final IRiskCaseRepository caseRepository;
    private final IRiskRuleVersionRepository ruleVersionRepository;
    private final IRiskPunishmentRepository punishmentRepository;
    private final IRiskTaskPort riskTaskPort;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    @Value("${risk.sample.passPercent:0}")
    private int passSamplePercent;

    @Value("${risk.sample.workStartHour:9}")
    private int sampleWorkStartHour;

    @Value("${risk.sample.workEndHour:21}")
    private int sampleWorkEndHour;

    @Value("${risk.sample.salt:}")
    private String sampleSalt;

    private static final String DEFAULT_RULES_JSON = "{"
            + "\"version\":1,"
            + "\"shadow\":false,"
            + "\"rules\":["
            + "{\"ruleId\":\"TEXT_LINK_BLOCK\",\"scenario\":\"*\",\"priority\":10,\"enabled\":true,\"shadow\":false,"
            + "\"when\":{\"type\":\"regex\",\"pattern\":\"(https?://|www\\\\.)\"},"
            + "\"then\":{\"type\":\"block\"},\"reasonCode\":\"SPAM_LINK\",\"tags\":[\"spam/link\"]},"
            + "{\"ruleId\":\"COMMENT_RATE_LIMIT_60S\",\"scenario\":\"comment.create\",\"priority\":20,\"enabled\":true,\"shadow\":false,"
            + "\"when\":{\"type\":\"rate_limit\",\"windowSeconds\":60,\"threshold\":20},"
            + "\"then\":{\"type\":\"limit\",\"ttlSeconds\":600},\"reasonCode\":\"SPAM_RATE_LIMIT\",\"tags\":[\"spam/rate\"]},"
            + "{\"ruleId\":\"POST_WITH_IMAGE_QUARANTINE\",\"scenario\":\"post.publish\",\"priority\":30,\"enabled\":true,\"shadow\":false,"
            + "\"when\":{\"type\":\"has_media\"},"
            + "\"then\":{\"type\":\"quarantine\"},\"reasonCode\":\"IMAGE_NEED_REVIEW\",\"tags\":[\"image\"]}"
            + "]"
            + "}";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RiskDecisionVO decision(RiskEventVO event) {
        RiskEventVO e = normalize(event);
        String requestHash = requestHash(e);
        RiskDecisionLogEntity existed = decisionLogRepository.findByUserEvent(e.getUserId(), e.getEventId());
        if (existed != null) {
            return replay(existed, requestHash);
        }
        RiskDecisionVO decision = decideNew(e);
        return persist(e, requestHash, decision);
    }

    @Override
    public TextScanResultVO textScan(String content, Long userId, String scenario) {
        RiskEventVO event = RiskEventVO.builder()
                .eventId("scan_text_" + socialIdPort.nextId())
                .userId(userId)
                .actionType("TEXT_SCAN")
                .scenario(scenario == null || scenario.isBlank() ? "text.scan" : scenario)
                .contentText(content)
                .occurTime(socialIdPort.now())
                .build();
        // 旧接口也走统一决策入口：保证每次判断都有审计落库（便于追溯误杀/漏放）。
        RiskDecisionVO decision = decision(event);
        String result = mapScanResult(decision.getResult());
        List<String> tags = flattenTags(decision.getSignals());
        if (tags.isEmpty()) {
            tags = List.of("clean");
        }
        return TextScanResultVO.builder().result(result).tags(tags).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImageScanResultVO imageScan(String imageUrl, Long userId) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "imageUrl 不能为空");
        }
        String taskId = "task-" + socialIdPort.nextId();
        RiskEventVO event = RiskEventVO.builder()
                .eventId(taskId)
                .userId(userId)
                .actionType("IMAGE_SCAN")
                .scenario("image.scan")
                .mediaUrls(List.of(imageUrl))
                .occurTime(socialIdPort.now())
                .build();
        RiskDecisionVO decision = RiskDecisionVO.builder()
                .decisionId(socialIdPort.nextId())
                .result("REVIEW")
                .reasonCode("IMAGE_ASYNC")
                .actions(List.of(RiskActionVO.builder().type("REVIEW_CREATE").paramsJson("{\"queue\":\"image\"}").build()))
                .signals(List.of(RiskSignalVO.builder().source("RULE").name("IMAGE_ASYNC").score(1D).tags(List.of("image")).build()))
                .build();
        persist(event, requestHash(event), decision);
        return ImageScanResultVO.builder().taskId(taskId).build();
    }

    @Override
    public UserRiskStatusVO userStatus(Long userId) {
        List<RiskPunishmentEntity> list = punishmentRepository.listActiveByUser(userId, socialIdPort.now());
        List<String> capabilities = new ArrayList<>(List.of("POST", "COMMENT"));
        String status = "NORMAL";
        if (hasType(list, "POST_BAN")) {
            capabilities.remove("POST");
        }
        if (hasType(list, "COMMENT_BAN")) {
            capabilities.remove("COMMENT");
        }
        if (hasType(list, "LOGIN_BAN")) {
            status = "FROZEN";
        }
        if (capabilities.isEmpty()) {
            status = "FROZEN";
        }
        return UserRiskStatusVO.builder().status(status).capabilities(capabilities).build();
    }

    private RiskDecisionVO replay(RiskDecisionLogEntity existed, String requestHash) {
        if (existed.getRequestHash() != null && !existed.getRequestHash().equals(requestHash)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "eventId 重复且请求体不一致，拒绝执行");
        }
        return RiskDecisionVO.builder()
                .decisionId(existed.getDecisionId())
                .result(existed.getResult())
                .reasonCode(existed.getReasonCode())
                .actions(parseList(existed.getActionsJson(), new TypeReference<List<RiskActionVO>>() {
                }))
                .signals(parseList(existed.getSignalsJson(), new TypeReference<List<RiskSignalVO>>() {
                }))
                .build();
    }

    private RiskDecisionVO decideNew(RiskEventVO event) {
        RiskDecisionVO byPunish = decideByPunishment(event);
        if (byPunish != null) {
            return byPunish;
        }
        RiskRuleSetVO ruleSet = loadActiveRules();
        return decideByRules(event, ruleSet);
    }

    private RiskDecisionVO decideByPunishment(RiskEventVO event) {
        List<RiskPunishmentEntity> list = punishmentRepository.listActiveByUser(event.getUserId(), socialIdPort.now());
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (isBlockedByPunishment(list, event.getActionType())) {
            RiskSignalVO signal = RiskSignalVO.builder()
                    .source("PUNISHMENT")
                    .name("PUNISHMENT_ACTIVE")
                    .score(1D)
                    .tags(List.of("punish"))
                    .build();
            return RiskDecisionVO.builder()
                    .decisionId(socialIdPort.nextId())
                    .result("BLOCK")
                    .reasonCode("PUNISHMENT_ACTIVE")
                    .actions(List.of(RiskActionVO.builder().type("BLOCK").paramsJson("{\"reason\":\"punishment\"}").build()))
                    .signals(List.of(signal))
                    .build();
        }
        return null;
    }

    private boolean isBlockedByPunishment(List<RiskPunishmentEntity> list, String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return false;
        }
        if (matchAction(actionType, "LOGIN", "REGISTER")) {
            return hasType(list, "LOGIN_BAN");
        }
        if (matchAction(actionType, "PUBLISH_POST", "EDIT_POST")) {
            return hasType(list, "POST_BAN");
        }
        if ("COMMENT_CREATE".equalsIgnoreCase(actionType)) {
            return hasType(list, "COMMENT_BAN");
        }
        if ("DM_SEND".equalsIgnoreCase(actionType)) {
            return hasType(list, "DM_BAN");
        }
        return false;
    }

    private RiskRuleSetVO loadActiveRules() {
        RiskRuleVersionEntity active = ruleVersionRepository.findActive();
        String json = active == null || active.getRulesJson() == null || active.getRulesJson().isBlank()
                ? DEFAULT_RULES_JSON
                : active.getRulesJson();
        try {
            RiskRuleSetVO rules = objectMapper.readValue(json, RiskRuleSetVO.class);
            if (rules.getRules() == null) {
                rules.setRules(List.of());
            }
            return rules;
        } catch (Exception e) {
            throw new IllegalStateException("rules_json 解析失败", e);
        }
    }

    private RiskDecisionVO decideByRules(RiskEventVO event, RiskRuleSetVO ruleSet) {
        List<RiskRuleVO> rules = ruleSet == null || ruleSet.getRules() == null ? List.of() : ruleSet.getRules();
        boolean globalShadow = ruleSet != null && Boolean.TRUE.equals(ruleSet.getShadow());
        boolean canaryEnforce = inCanary(ruleSet, event == null ? null : event.getUserId());
        List<RiskRuleVO> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(r -> r.getPriority() == null ? Integer.MAX_VALUE : r.getPriority()));

        List<RiskSignalVO> signals = new ArrayList<>();
        Map<String, Long> counters = new HashMap<>();
        for (RiskRuleVO rule : sorted) {
            RiskDecisionVO decided = tryApplyRule(event, rule, globalShadow, canaryEnforce, signals, counters);
            if (decided != null) {
                return decided;
            }
        }
        return RiskDecisionVO.builder()
                .decisionId(socialIdPort.nextId())
                .result("PASS")
                .reasonCode("PASS")
                .actions(List.of(RiskActionVO.builder().type("ALLOW").paramsJson("{}").build()))
                .signals(signals)
                .build();
    }

    private RiskDecisionVO tryApplyRule(RiskEventVO event, RiskRuleVO rule, boolean globalShadow, boolean canaryEnforce, List<RiskSignalVO> signals, Map<String, Long> counters) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            return null;
        }
        if (!matchScenario(rule.getScenario(), event.getScenario())) {
            return null;
        }
        if (!hit(rule.getWhen(), event, counters)) {
            return null;
        }
        signals.add(RiskSignalVO.builder()
                .source("RULE")
                .name(rule.getRuleId())
                .score(1D)
                .tags(rule.getTags() == null ? List.of() : rule.getTags())
                .detailJson(toJsonSafe(rule.getWhen()))
                .build());

        // canaryEnforce=false 等价“只记录不生效”（影子生效），用于灰度开闸。
        boolean shadow = globalShadow || !canaryEnforce || Boolean.TRUE.equals(rule.getShadow());
        if (shadow) {
            return null;
        }
        return buildDecisionFromThen(rule, signals);
    }

    private boolean inCanary(RiskRuleSetVO ruleSet, Long userId) {
        if (ruleSet == null) {
            return true;
        }
        Integer p = ruleSet.getCanaryPercent();
        if (p == null) {
            return true;
        }
        int percent = Math.max(0, Math.min(p, 100));
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        int salt = ruleSet.getCanarySalt() == null ? 0 : ruleSet.getCanarySalt().hashCode();
        long uid = userId == null ? 0L : userId;
        int bucket = Math.floorMod(uid + salt, 100);
        return bucket < percent;
    }

    private RiskDecisionVO buildDecisionFromThen(RiskRuleVO rule, List<RiskSignalVO> signals) {
        Map<String, Object> then = rule.getThen() == null ? Map.of() : rule.getThen();
        String type = String.valueOf(then.getOrDefault("type", "allow"));
        if ("block".equalsIgnoreCase(type)) {
            return RiskDecisionVO.builder()
                    .decisionId(socialIdPort.nextId())
                    .result("BLOCK")
                    .reasonCode(safe(rule.getReasonCode(), "RULE_BLOCK"))
                    .actions(List.of(RiskActionVO.builder().type("BLOCK").paramsJson("{\"reason\":\"rule\"}").build()))
                    .signals(signals)
                    .build();
        }
        if ("limit".equalsIgnoreCase(type)) {
            int ttl = toInt(then.get("ttlSeconds"), 600);
            return RiskDecisionVO.builder()
                    .decisionId(socialIdPort.nextId())
                    .result("LIMIT")
                    .reasonCode(safe(rule.getReasonCode(), "RULE_LIMIT"))
                    .actions(List.of(RiskActionVO.builder().type("RATE_LIMIT").paramsJson("{\"ttlSeconds\":" + ttl + "}").build()))
                    .signals(signals)
                    .ttlSeconds(ttl)
                    .build();
        }
        if ("quarantine".equalsIgnoreCase(type)) {
            return RiskDecisionVO.builder()
                    .decisionId(socialIdPort.nextId())
                    .result("REVIEW")
                    .reasonCode(safe(rule.getReasonCode(), "RULE_REVIEW"))
                    .actions(List.of(RiskActionVO.builder().type("DEGRADE_VISIBILITY").paramsJson("{\"visibility\":\"QUARANTINE\"}").build()))
                    .signals(signals)
                    .build();
        }
        return RiskDecisionVO.builder()
                .decisionId(socialIdPort.nextId())
                .result("PASS")
                .reasonCode(safe(rule.getReasonCode(), "PASS"))
                .actions(List.of(RiskActionVO.builder().type("ALLOW").paramsJson("{}").build()))
                .signals(signals)
                .build();
    }

    private boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        if (when == null || when.isEmpty()) {
            return false;
        }
        String type = String.valueOf(when.getOrDefault("type", ""));
        if ("has_media".equalsIgnoreCase(type)) {
            return event.getMediaUrls() != null && !event.getMediaUrls().isEmpty();
        }
        if ("text_contains_any".equalsIgnoreCase(type)) {
            return hitTextContainsAny(when, event.getContentText());
        }
        if ("regex".equalsIgnoreCase(type)) {
            return hitRegex(when, event.getContentText());
        }
        if ("rate_limit".equalsIgnoreCase(type)) {
            return hitRateLimit(when, event, counters);
        }
        return false;
    }

    private boolean hitTextContainsAny(Map<String, Object> when, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Object k = when.get("keywords");
        if (!(k instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        boolean ci = Boolean.TRUE.equals(when.get("caseInsensitive"));
        String content = ci ? text.toLowerCase() : text;
        for (Object raw : list) {
            if (raw == null) {
                continue;
            }
            String kw = String.valueOf(raw);
            String needle = ci ? kw.toLowerCase() : kw;
            if (!needle.isBlank() && content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean hitRegex(Map<String, Object> when, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Object p = when.get("pattern");
        if (p == null) {
            return false;
        }
        Pattern pattern = Pattern.compile(String.valueOf(p), Pattern.CASE_INSENSITIVE);
        return pattern.matcher(text).find();
    }

    private boolean hitRateLimit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        Integer window = toIntObj(when.get("windowSeconds"));
        Integer threshold = toIntObj(when.get("threshold"));
        if (window == null || threshold == null || window <= 0) {
            return false;
        }
        long cnt = incrCounter(event.getUserId(), event.getActionType(), window, counters);
        return cnt > threshold;
    }

    private long incrCounter(Long userId, String actionType, int windowSeconds, Map<String, Long> counters) {
        if (userId == null || actionType == null || actionType.isBlank()) {
            return 0L;
        }
        String key = "risk:cnt:" + userId + ":" + actionType + ":" + windowSeconds;
        if (counters != null) {
            Long cached = counters.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long v = counter.incrementAndGet();
        counter.expire(Duration.ofSeconds(windowSeconds));
        if (counters != null) {
            counters.put(key, v);
        }
        return v;
    }

    private RiskDecisionVO persist(RiskEventVO event, String requestHash, RiskDecisionVO decision) {
        RiskDecisionLogEntity log = RiskDecisionLogEntity.builder()
                .decisionId(decision.getDecisionId())
                .eventId(event.getEventId())
                .userId(event.getUserId())
                .actionType(event.getActionType())
                .scenario(event.getScenario())
                .result(decision.getResult())
                .reasonCode(decision.getReasonCode())
                .requestHash(requestHash)
                .signalsJson(toJsonSafe(decision.getSignals()))
                .actionsJson(toJsonSafe(decision.getActions()))
                .extJson(event.getExtJson())
                .traceId("")
                .createTime(socialIdPort.now())
                .updateTime(socialIdPort.now())
                .build();
        boolean inserted = decisionLogRepository.insert(log);
        if (inserted) {
            RiskCaseEntity createdCase = tryCreateCase(event, decision);
            dispatchTasksAfterCommit(event, decision, createdCase);
            return decision;
        }
        // 并发幂等：可能另一个请求先插入成功，这里回读做一致性校验即可。
        RiskDecisionLogEntity existed = decisionLogRepository.findByUserEvent(event.getUserId(), event.getEventId());
        if (existed == null) {
            return decision;
        }
        if (existed.getRequestHash() != null && !existed.getRequestHash().equals(requestHash)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "eventId 并发重复且请求体不一致，拒绝执行");
        }
        return replay(existed, requestHash);
    }

    private RiskCaseEntity tryCreateCase(RiskEventVO event, RiskDecisionVO decision) {
        if (event == null || decision == null) {
            return null;
        }
        boolean needCase = false;
        String queue;
        if ("REVIEW".equalsIgnoreCase(decision.getResult())) {
            needCase = needReviewCase(event.getActionType());
            queue = chooseQueue(event);
        } else if ("PASS".equalsIgnoreCase(decision.getResult()) && shouldSamplePass(event)) {
            // PASS 抽检：不改变用户可见结果，仅创建工单供运营抽检。
            needCase = true;
            queue = "sample";
        } else {
            return null;
        }
        if (!needCase) {
            return null;
        }

        RiskCaseEntity entity = RiskCaseEntity.builder()
                .caseId(socialIdPort.nextId())
                .decisionId(decision.getDecisionId())
                .status("OPEN")
                .queue(queue)
                .assignee(null)
                .result("")
                .evidenceJson(buildEvidenceJson(event, decision))
                .createTime(socialIdPort.now())
                .updateTime(socialIdPort.now())
                .build();
        caseRepository.insertIgnore(entity);
        RiskCaseEntity existed = caseRepository.findByDecisionId(decision.getDecisionId());
        return existed == null ? entity : existed;
    }

    private void dispatchTasksAfterCommit(RiskEventVO event, RiskDecisionVO decision, RiskCaseEntity createdCase) {
        if (event == null || decision == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatchTasksBestEffort(event, decision, createdCase);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchTasksBestEffort(event, decision, createdCase);
            }
        });
    }

    private void dispatchTasksBestEffort(RiskEventVO event, RiskDecisionVO decision, RiskCaseEntity createdCase) {
        try {
            if (createdCase != null) {
                ReviewCaseCreatedEvent evt = new ReviewCaseCreatedEvent();
                evt.setCaseId(createdCase.getCaseId());
                evt.setDecisionId(createdCase.getDecisionId());
                evt.setQueue(createdCase.getQueue());
                evt.setSummary("sample".equalsIgnoreCase(createdCase.getQueue()) ? "risk sample" : "risk review");
                riskTaskPort.dispatchReviewCase(evt);
            }

            // 图片扫描接口：只投递图片扫描任务（不阻塞在线）。
            if ("IMAGE_SCAN".equalsIgnoreCase(event.getActionType())) {
                ImageScanRequestedEvent evt = new ImageScanRequestedEvent();
                evt.setTaskId(event.getEventId());
                evt.setDecisionId(decision.getDecisionId());
                evt.setEventId(event.getEventId());
                evt.setUserId(event.getUserId());
                evt.setImageUrl(firstUrl(event.getMediaUrls()));
                riskTaskPort.dispatchImageScan(evt);
                return;
            }

            // 业务写动作：REVIEW 时投递 LLM 扫描任务（文本/图片）。
            if (!needAsyncScan(event.getActionType(), decision.getResult())) {
                return;
            }
            if (event.getContentText() != null && !event.getContentText().isBlank()) {
                riskTaskPort.dispatchLlmScan(buildLlmScanEvent(event, decision, "TEXT"));
            }
            if (event.getMediaUrls() != null && !event.getMediaUrls().isEmpty()) {
                riskTaskPort.dispatchLlmScan(buildLlmScanEvent(event, decision, "IMAGE"));
            }
        } catch (Exception e) {
            // MQ 投递失败不允许拖死在线链路：记录日志，后续靠补偿/重试排查。
            log.warn("risk async task dispatch failed, decisionId={}, eventId={}", decision.getDecisionId(), event.getEventId(), e);
        }
    }

    private boolean needReviewCase(String actionType) {
        if (actionType == null) {
            return false;
        }
        return "PUBLISH_POST".equalsIgnoreCase(actionType)
                || "EDIT_POST".equalsIgnoreCase(actionType)
                || "COMMENT_CREATE".equalsIgnoreCase(actionType)
                || "DM_SEND".equalsIgnoreCase(actionType);
    }

    private boolean shouldSamplePass(RiskEventVO event) {
        int percent = Math.max(0, Math.min(passSamplePercent, 100));
        if (percent <= 0) {
            return false;
        }
        if (event == null || event.getActionType() == null) {
            return false;
        }
        // 仅对本次上线范围内的低频写动作抽检，避免引入无意义的工单噪音。
        if (!needReviewCase(event.getActionType())) {
            return false;
        }
        if (!inWorkHours(socialIdPort.now(), sampleWorkStartHour, sampleWorkEndHour)) {
            return false;
        }
        long uid = event.getUserId() == null ? 0L : event.getUserId();
        int salt = sampleSalt == null ? 0 : sampleSalt.hashCode();
        int eh = event.getEventId() == null ? 0 : event.getEventId().hashCode();
        int bucket = Math.floorMod((int) (uid ^ (uid >>> 32)) + eh + salt, 100);
        return bucket < percent;
    }

    private boolean inWorkHours(long nowMs, int startHourRaw, int endHourRaw) {
        int start = Math.max(0, Math.min(startHourRaw, 23));
        int end = Math.max(0, Math.min(endHourRaw, 23));
        // 配置异常时：直接当成不限制（避免把线上抽检逻辑写死）
        if (end <= start) {
            return true;
        }
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.of("Asia/Shanghai"));
        int h = zdt.getHour();
        return h >= start && h < end;
    }

    private boolean needAsyncScan(String actionType, String result) {
        if (actionType == null) {
            return false;
        }
        // 只对“业务写动作”且为 REVIEW 的情况投递（避免把低价值动作打爆成本）。
        if (!"REVIEW".equalsIgnoreCase(result)) {
            return false;
        }
        return "PUBLISH_POST".equalsIgnoreCase(actionType)
                || "EDIT_POST".equalsIgnoreCase(actionType)
                || "COMMENT_CREATE".equalsIgnoreCase(actionType)
                || "DM_SEND".equalsIgnoreCase(actionType);
    }

    private String chooseQueue(RiskEventVO event) {
        String scenario = event == null ? null : event.getScenario();
        if (scenario != null) {
            String s = scenario.toLowerCase();
            if (s.contains("comment")) {
                return "comment";
            }
            if (s.contains("post")) {
                return "post";
            }
            if (s.contains("image")) {
                return "image";
            }
        }
        return "default";
    }

    private String buildEvidenceJson(RiskEventVO event, RiskDecisionVO decision) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", event.getEventId());
        m.put("userId", event.getUserId());
        m.put("actionType", event.getActionType());
        m.put("scenario", event.getScenario());
        m.put("targetId", event.getTargetId());
        m.put("contentText", truncate(event.getContentText(), 500));
        m.put("mediaUrls", event.getMediaUrls());
        m.put("extJson", event.getExtJson());
        m.put("signals", decision.getSignals());
        return toJsonSafe(m);
    }

    private LlmScanRequestedEvent buildLlmScanEvent(RiskEventVO event, RiskDecisionVO decision, String contentType) {
        LlmScanRequestedEvent evt = new LlmScanRequestedEvent();
        evt.setTaskId("llm-" + decision.getDecisionId() + "-" + contentType.toLowerCase());
        evt.setDecisionId(decision.getDecisionId());
        evt.setEventId(event.getEventId());
        evt.setUserId(event.getUserId());
        evt.setActionType(event.getActionType());
        evt.setScenario(event.getScenario());
        evt.setContentText("TEXT".equalsIgnoreCase(contentType) ? event.getContentText() : null);
        evt.setMediaUrls("IMAGE".equalsIgnoreCase(contentType) ? event.getMediaUrls() : null);
        evt.setTargetId(event.getTargetId());
        evt.setExtJson(event.getExtJson());
        evt.setContentType(contentType);
        evt.setContentHash(contentHash(event, contentType));
        return evt;
    }

    private String contentHash(RiskEventVO event, String contentType) {
        if (event == null || contentType == null) {
            return "";
        }
        if ("TEXT".equalsIgnoreCase(contentType)) {
            String text = event.getContentText();
            if (text == null || text.isBlank()) {
                return "";
            }
            return sha256Base64Url(text.trim());
        }
        String url = firstUrl(event.getMediaUrls());
        if (url == null || url.isBlank()) {
            return "";
        }
        return sha256Base64Url(url.trim());
    }

    private String firstUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        for (String u : urls) {
            if (u != null && !u.isBlank()) {
                return u;
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private RiskEventVO normalize(RiskEventVO event) {
        if (event == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "event 不能为空");
        }
        if (event.getUserId() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId 不能为空");
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "eventId 不能为空");
        }
        if (event.getActionType() == null || event.getActionType().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "actionType 不能为空");
        }
        if (event.getScenario() == null || event.getScenario().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "scenario 不能为空");
        }
        if (event.getOccurTime() == null) {
            event.setOccurTime(socialIdPort.now());
        }
        if (event.getMediaUrls() != null && event.getMediaUrls().size() > 20) {
            event.setMediaUrls(event.getMediaUrls().subList(0, 20));
        }
        return event;
    }

    private String requestHash(RiskEventVO e) {
        String seed = e.getEventId() + "|" + e.getUserId() + "|" + e.getActionType() + "|" + e.getScenario() + "|"
                + safe(e.getContentText(), "") + "|"
                + join(e.getMediaUrls()) + "|"
                + safe(e.getTargetId(), "") + "|"
                + safe(e.getExtJson(), "");
        return sha256Base64Url(seed);
    }

    private String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(",", list);
    }

    private String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("hash failed", e);
        }
    }

    private boolean matchScenario(String ruleScenario, String eventScenario) {
        if (eventScenario == null) {
            return false;
        }
        if (ruleScenario == null || ruleScenario.isBlank() || "*".equals(ruleScenario)) {
            return true;
        }
        return ruleScenario.equalsIgnoreCase(eventScenario);
    }

    private boolean matchAction(String actionType, String a1, String a2) {
        return a1.equalsIgnoreCase(actionType) || a2.equalsIgnoreCase(actionType);
    }

    private boolean hasType(List<RiskPunishmentEntity> list, String type) {
        if (list == null || list.isEmpty() || type == null || type.isBlank()) {
            return false;
        }
        for (RiskPunishmentEntity e : list) {
            if (e != null && type.equalsIgnoreCase(e.getType())) {
                return true;
            }
        }
        return false;
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> ref) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> list = objectMapper.readValue(json, ref);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJsonSafe(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private int toInt(Object v, int def) {
        Integer i = toIntObj(v);
        return i == null ? def : i;
    }

    private Integer toIntObj(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private String mapScanResult(String decisionResult) {
        if ("BLOCK".equalsIgnoreCase(decisionResult)) {
            return "BLOCK";
        }
        if ("REVIEW".equalsIgnoreCase(decisionResult)) {
            return "REVIEW";
        }
        return "PASS";
    }

    private List<String> flattenTags(List<RiskSignalVO> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        Map<String, Boolean> uniq = new HashMap<>();
        for (RiskSignalVO s : signals) {
            if (s == null || s.getTags() == null) {
                continue;
            }
            for (String t : s.getTags()) {
                if (t != null && !t.isBlank()) {
                    uniq.put(t, true);
                }
            }
        }
        return new ArrayList<>(uniq.keySet());
    }
}
