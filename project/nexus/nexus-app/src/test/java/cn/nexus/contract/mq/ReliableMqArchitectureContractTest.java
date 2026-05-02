package cn.nexus.contract.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReliableMqArchitectureContractTest {

    private static final Set<String> RAW_PUBLISH_ALLOWED_FILES = Set.of(
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqOutboxService.java",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqReplayService.java",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/port/UserEventOutboxPort.java"
    );

    private static final Set<String> BEST_EFFORT_LISTENERS_FROM_INVENTORY = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java:onMessage"
    );

    private static final Set<String> MANUAL_ACK_LISTENERS_FROM_INVENTORY = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onFollow",
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onBlock"
    );

    private static final Set<String> SIDE_EFFECTING_LISTENER_FILES = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackAConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java",
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java"
    );

    private static final List<String> EXPECTED_RAW_PUBLISH_FINDINGS = List.of(
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:43:rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:59:rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_UPDATED, event);",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:75:rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_DELETED, event);",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionNotifyMqPort.java:20:rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionRecommendFeedbackMqPort.java:20:rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java:51:operations.convertAndSend(RelationCounterRouting.EXCHANGE, routingKey, event);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java:132:rabbitTemplate.convertAndSend(FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.TASK_ROUTING_KEY, task);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java:183:rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_SCAN_COMPLETED, evt);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java:286:rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_SCAN_COMPLETED, evt);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java:94:rabbitTemplate.convertAndSend(publishExchange, publishRoutingKey, event);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskProducer.java:26:rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_LLM_SCAN, event);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskProducer.java:34:rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_IMAGE_SCAN, event);",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskProducer.java:42:rabbitTemplate.convertAndSend(RiskMqConfig.EXCHANGE, RiskMqConfig.RK_REVIEW_CASE, event);"
    );

    private static final List<String> EXPECTED_UNANNOTATED_LISTENER_FINDINGS = List.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackAConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateConsumer.java:onPostSummaryGenerate",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcConsumer.java:onPostChanged",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java:onRaw",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java:onUserNicknameChanged"
    );

    private static final Pattern METHOD_AFTER_RABBIT_LISTENER = Pattern.compile(
            "@RabbitListener[\\s\\S]*?\\n\\s*public\\s+[^\\n=;]+?\\s+(\\w+)\\s*\\(");

    @Test
    void rawRabbitTemplatePublishesRemainAtCurrentAuditBaseline() throws IOException {
        List<String> findings = javaSources().stream()
                .filter(path -> !isTestSource(path))
                .filter(path -> !RAW_PUBLISH_ALLOWED_FILES.contains(relative(path)))
                .flatMap(path -> rawPublishFindings(path).stream())
                .sorted()
                .toList();

        assertEquals(EXPECTED_RAW_PUBLISH_FINDINGS, findings);
    }

    @Test
    void sideEffectingRabbitListenersRemainAtCurrentAuditBaseline() throws IOException {
        List<String> findings = javaSources().stream()
                .filter(path -> SIDE_EFFECTING_LISTENER_FILES.contains(relative(path)))
                .flatMap(path -> unannotatedListenerFindings(path).stream())
                .sorted()
                .toList();

        assertEquals(EXPECTED_UNANNOTATED_LISTENER_FINDINGS, findings);
    }

    private static List<Path> javaSources() throws IOException {
        Path root = nexusRoot();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
    }

    private static List<String> rawPublishFindings(Path path) {
        String source = read(path);
        List<String> findings = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("rabbitTemplate.convertAndSend(") || line.contains(".convertAndSend(")) {
                findings.add(relative(path) + ":" + (i + 1) + ":" + line.trim());
            }
        }
        return findings;
    }

    private static List<String> unannotatedListenerFindings(Path path) {
        String source = read(path);
        Matcher matcher = METHOD_AFTER_RABBIT_LISTENER.matcher(source);
        List<String> findings = new ArrayList<>();
        while (matcher.find()) {
            String method = matcher.group(1);
            String id = relative(path) + ":" + method;
            if (hasReliableMqConsumeAnnotation(source, matcher.start(), matcher.end())
                    || BEST_EFFORT_LISTENERS_FROM_INVENTORY.contains(id)
                    || MANUAL_ACK_LISTENERS_FROM_INVENTORY.contains(id)) {
                continue;
            }
            findings.add(id);
        }
        return findings;
    }

    private static boolean hasReliableMqConsumeAnnotation(String source, int rabbitListenerStart, int methodDeclarationEnd) {
        if (source.substring(rabbitListenerStart, methodDeclarationEnd).contains("@ReliableMqConsume")) {
            return true;
        }
        int lineStart = source.lastIndexOf('\n', rabbitListenerStart);
        int scan = lineStart <= 0 ? 0 : lineStart - 1;
        while (scan > 0) {
            int previousLineStart = source.lastIndexOf('\n', scan);
            int start = previousLineStart < 0 ? 0 : previousLineStart + 1;
            String line = source.substring(start, scan + 1).trim();
            if (line.isEmpty()) {
                scan = previousLineStart - 1;
                continue;
            }
            if (!line.startsWith("@")) {
                return false;
            }
            if (line.startsWith("@ReliableMqConsume") || line.startsWith("@cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume")) {
                return true;
            }
            scan = previousLineStart - 1;
        }
        return false;
    }

    private static boolean isTestSource(Path path) {
        return relative(path).contains("/src/test/");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static String relative(Path path) {
        return nexusRoot().relativize(path).toString().replace('\\', '/');
    }

    private static Path nexusRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("nexus-app"))) {
            return current;
        }
        for (Path p = current; p != null; p = p.getParent()) {
            Path projectNexus = p.resolve("project/nexus");
            if (Files.isDirectory(projectNexus.resolve("nexus-app"))) {
                return projectNexus;
            }
            if (Files.isDirectory(p.resolve("nexus-app"))) {
                return p;
            }
        }
        throw new IllegalStateException("Cannot resolve project/nexus root from " + current);
    }
}
