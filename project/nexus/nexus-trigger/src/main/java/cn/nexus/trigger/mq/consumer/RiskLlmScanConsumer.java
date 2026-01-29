package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.service.risk.RiskAsyncService;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.types.event.risk.ScanCompletedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 风控 LLM 扫描消费者：文本/图片任务统一走该队列（以 contentType 区分）。
 *
 * <p>设计要点：去重缓存 + inflight 锁 + 分钟预算器；LLM 永不阻塞在线链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskLlmScanConsumer {

    private static final String CACHE_KEY_PREFIX = "risk:llm:cache:";
    private static final String INFLIGHT_KEY_PREFIX = "risk:llm:inflight:";
    private static final String BUDGET_KEY_PREFIX = "risk:llm:budget:";

    private static final DateTimeFormatter BUDGET_MIN_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final RedissonClient redissonClient;
    private final IMediaStoragePort mediaStoragePort;
    private final IRiskLlmPort llmPort;
    private final RiskAsyncService riskAsyncService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${risk.llm.budgetPerMinute:200}")
    private long budgetPerMinute;

    @Value("${risk.llm.cacheTtlSeconds:86400}")
    private long cacheTtlSeconds;

    @Value("${risk.llm.inflightLockSeconds:30}")
    private long inflightLockSeconds;

    @RabbitListener(queues = RiskMqConfig.Q_LLM_SCAN)
    public void onMessage(LlmScanRequestedEvent event) {
        if (event == null || event.getDecisionId() == null) {
            return;
        }
        String contentType = normalizeContentType(event.getContentType());
        String hash = normalizeHash(event.getContentHash(), contentType, event.getContentText(), event.getMediaUrls());

        RiskLlmResultVO cached = getCached(hash);
        if (cached != null) {
            riskAsyncService.applyLlmResult(event.getDecisionId(), cached);
            publishCompleted(event, cached);
            return;
        }

        if (!acquireBudget(contentType)) {
            RiskLlmResultVO fb = fallback(contentType, "LLM_BUDGET_EXCEEDED");
            riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
            publishCompleted(event, fb);
            return;
        }

        if (hash == null || hash.isBlank()) {
            RiskLlmResultVO fb = fallback(contentType, "LLM_HASH_EMPTY");
            riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
            publishCompleted(event, fb);
            return;
        }

        String lockKey = INFLIGHT_KEY_PREFIX + hash;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(200, TimeUnit.SECONDS.toMillis(inflightLockSeconds), TimeUnit.MILLISECONDS);
            if (!locked) {
                // 另一条消息正在扫同一份内容：不重复调用 LLM，直接跳过（等待缓存命中/人工处理）。
                log.debug("risk llm scan inflight, decisionId={}, hash={}", event.getDecisionId(), hash);
                return;
            }

            RiskLlmResultVO again = getCached(hash);
            if (again != null) {
                riskAsyncService.applyLlmResult(event.getDecisionId(), again);
                publishCompleted(event, again);
                return;
            }

            RiskLlmResultVO result = callLlm(event, contentType);
            if (result != null) {
                cache(hash, result);
                riskAsyncService.applyLlmResult(event.getDecisionId(), result);
                publishCompleted(event, result);
            } else {
                RiskLlmResultVO fb = fallback(contentType, "LLM_EMPTY_RESULT");
                riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
                publishCompleted(event, fb);
            }
        } catch (Exception e) {
            log.warn("risk llm scan failed, decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId(), e);
            RiskLlmResultVO fb = fallback(contentType, "LLM_CALL_FAILED");
            riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
            publishCompleted(event, fb);
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private RiskLlmResultVO callLlm(LlmScanRequestedEvent event, String contentType) {
        String scenario = event.getScenario();
        String actionType = event.getActionType();
        String extJson = event.getExtJson();
        if ("IMAGE".equalsIgnoreCase(contentType)) {
            List<String> urls = toReadableUrls(event.getMediaUrls());
            return llmPort.scanImage(scenario, actionType, urls, extJson);
        }
        String text = event.getContentText();
        if (text != null && text.length() > 8000) {
            text = text.substring(0, 8000);
        }
        return llmPort.scanText(scenario, actionType, text, extJson);
    }

    private List<String> toReadableUrls(List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }
        List<String> res = new ArrayList<>();
        for (String raw : mediaUrls) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim();
            if (token.startsWith("http://") || token.startsWith("https://")) {
                res.add(token);
                continue;
            }
            String url = mediaStoragePort.generateReadUrl(token);
            if (url != null && !url.isBlank()) {
                res.add(url);
            }
        }
        return res;
    }

    private boolean acquireBudget(String contentType) {
        if (budgetPerMinute <= 0) {
            return true;
        }
        String minute = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(BUDGET_MIN_FMT);
        String key = BUDGET_KEY_PREFIX + minute + ":" + contentType;
        RAtomicLong cnt = redissonClient.getAtomicLong(key);
        long v = cnt.incrementAndGet();
        cnt.expire(Duration.ofMinutes(2));
        return v <= budgetPerMinute;
    }

    private RiskLlmResultVO getCached(String hash) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(CACHE_KEY_PREFIX + hash);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, RiskLlmResultVO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void cache(String hash, RiskLlmResultVO result) {
        if (hash == null || hash.isBlank() || result == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            RBucket<String> bucket = redissonClient.getBucket(CACHE_KEY_PREFIX + hash);
            bucket.set(json, Duration.ofSeconds(Math.max(30, cacheTtlSeconds)));
        } catch (Exception ignored) {
        }
    }

    private RiskLlmResultVO fallback(String contentType, String reasonCode) {
        return RiskLlmResultVO.builder()
                .contentType(contentType)
                .result("REVIEW")
                .riskTags(List.of())
                .confidence(0D)
                .reasonCode(reasonCode)
                .evidence(null)
                .suggestedAction("QUARANTINE")
                .build();
    }

    private String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "TEXT";
        }
        if ("IMAGE".equalsIgnoreCase(raw)) {
            return "IMAGE";
        }
        return "TEXT";
    }

    private String normalizeHash(String hash, String contentType, String text, List<String> mediaUrls) {
        if (hash != null && !hash.isBlank()) {
            return hash;
        }
        if ("IMAGE".equalsIgnoreCase(contentType)) {
            String url = firstUrl(mediaUrls);
            return url == null ? "" : sha256Base64Url(url.trim());
        }
        if (text == null || text.isBlank()) {
            return "";
        }
        return sha256Base64Url(text.trim());
    }

    private void publishCompleted(LlmScanRequestedEvent req, RiskLlmResultVO result) {
        if (req == null || req.getDecisionId() == null || rabbitTemplate == null) {
            return;
        }
        try {
            ScanCompletedEvent evt = new ScanCompletedEvent();
            evt.setDecisionId(req.getDecisionId());
            evt.setTaskId(req.getTaskId());
            evt.setContentType(result == null ? null : result.getContentType());
            evt.setResult(result == null ? null : result.getResult());
            evt.setReasonCode(result == null ? null : result.getReasonCode());
            evt.setConfidence(result == null ? null : result.getConfidence());
            evt.setRiskTags(result == null ? null : result.getRiskTags());
            evt.setPromptVersion(result == null ? null : result.getPromptVersion());
            evt.setModel(result == null ? null : result.getModel());
            rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_SCAN_COMPLETED, evt);
        } catch (Exception ignored) {
        }
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

    private String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
