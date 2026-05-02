package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.service.risk.RiskAsyncService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.trigger.mq.producer.RiskScanCompletedProducer;
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
 * @author rr
 * @author codex
 * @since 2026-01-29
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
    private final RiskScanCompletedProducer riskScanCompletedProducer;

    @Value("${risk.llm.budgetPerMinute:200}")
    private long budgetPerMinute;

    @Value("${risk.llm.cacheTtlSeconds:86400}")
    private long cacheTtlSeconds;

    @Value("${risk.llm.inflightLockSeconds:30}")
    private long inflightLockSeconds;

    /**
     * 消费单条消息。
     *
     * @param event 事件对象。类型：{@link LlmScanRequestedEvent}
     */
    @RabbitListener(queues = RiskMqConfig.Q_LLM_SCAN, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "RiskLlmScanConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(LlmScanRequestedEvent event) {
        if (event == null || event.getDecisionId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("risk llm scan payload invalid");
        }
        String contentType = normalizeContentType(event.getContentType());
        String hash = normalizeHash(event.getContentHash(), contentType, event.getContentText(), event.getMediaUrls());

        try {
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
                    throw new IllegalStateException("risk llm scan inflight lock busy");
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
            } finally {
                if (locked) {
                    try {
                        lock.unlock();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("risk llm scan interrupted", e);
        } catch (RuntimeException e) {
            log.warn("risk llm scan failed, decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId(), e);
            throw e;
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
        if (req == null || req.getDecisionId() == null) {
            return;
        }
        ScanCompletedEvent evt = new ScanCompletedEvent();
        evt.setEventId(req.getEventId() + ":completed");
        evt.setDecisionId(req.getDecisionId());
        evt.setTaskId(req.getTaskId());
        evt.setContentType(result == null ? null : result.getContentType());
        evt.setResult(result == null ? null : result.getResult());
        evt.setReasonCode(result == null ? null : result.getReasonCode());
        evt.setConfidence(result == null ? null : result.getConfidence());
        evt.setRiskTags(result == null ? null : result.getRiskTags());
        evt.setPromptVersion(result == null ? null : result.getPromptVersion());
        evt.setModel(result == null ? null : result.getModel());
        riskScanCompletedProducer.publish(evt);
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
