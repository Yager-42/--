package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import cn.nexus.domain.social.service.risk.RiskAsyncService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.ScanCompletedEvent;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 图片扫描消费者：图片扫描通常较慢且昂贵，因此只走异步队列。
 *
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskImageScanConsumer {

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

    /**
     * 消费单条消息。
     *
     * @param event 事件对象。类型：{@link ImageScanRequestedEvent}
     */
    @RabbitListener(queues = RiskMqConfig.Q_IMAGE_SCAN, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "RiskImageScanConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(ImageScanRequestedEvent event) {
        if (event == null || event.getDecisionId() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("risk image scan payload invalid");
        }
        String url = event.getImageUrl();
        String hash = url == null || url.isBlank() ? "" : sha256Base64Url(url.trim());

        try {
            RiskLlmResultVO cached = getCached(hash);
            if (cached != null) {
                riskAsyncService.applyLlmResult(event.getDecisionId(), cached);
                publishCompleted(event, cached);
                return;
            }

            if (!acquireBudget("IMAGE")) {
                RiskLlmResultVO fb = fallback("IMAGE", "LLM_BUDGET_EXCEEDED");
                riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
                publishCompleted(event, fb);
                return;
            }

            if (hash.isBlank()) {
                RiskLlmResultVO fb = fallback("IMAGE", "IMAGE_URL_EMPTY");
                riskAsyncService.applyLlmResult(event.getDecisionId(), fb);
                publishCompleted(event, fb);
                return;
            }

            RLock lock = redissonClient.getLock(INFLIGHT_KEY_PREFIX + hash);
            boolean locked = false;
            try {
                locked = lock.tryLock(200, TimeUnit.SECONDS.toMillis(inflightLockSeconds), TimeUnit.MILLISECONDS);
                if (!locked) {
                    throw new IllegalStateException("risk image scan inflight lock busy");
                }
                RiskLlmResultVO again = getCached(hash);
                if (again != null) {
                    riskAsyncService.applyLlmResult(event.getDecisionId(), again);
                    publishCompleted(event, again);
                    return;
                }

                String readUrl = toReadableUrl(url);
                RiskLlmResultVO res = llmPort.scanImage("image.scan", "IMAGE_SCAN", List.of(readUrl), null);
                if (res != null) {
                    res.setContentType("IMAGE");
                    cache(hash, res);
                    riskAsyncService.applyLlmResult(event.getDecisionId(), res);
                    publishCompleted(event, res);
                } else {
                    RiskLlmResultVO fb = fallback("IMAGE", "LLM_EMPTY_RESULT");
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
            throw new IllegalStateException("risk image scan interrupted", e);
        } catch (RuntimeException e) {
            log.warn("risk image scan failed, decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId(), e);
            throw e;
        }
    }

    private String toReadableUrl(String tokenOrUrl) {
        if (tokenOrUrl == null || tokenOrUrl.isBlank()) {
            return "";
        }
        String s = tokenOrUrl.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        String url = mediaStoragePort.generateReadUrl(s);
        return url == null ? s : url;
    }

    private void publishCompleted(ImageScanRequestedEvent req, RiskLlmResultVO result) {
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
