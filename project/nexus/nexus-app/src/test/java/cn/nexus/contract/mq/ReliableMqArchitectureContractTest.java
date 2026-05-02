package cn.nexus.contract.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReliableMqArchitectureContractTest {

    private static final Set<String> RAW_PUBLISH_ALLOWED_KEYS = Set.of(
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqOutboxService.java:publishReady:record.getExchangeName()|record.getRoutingKey()",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqReplayService.java:replayReady:record.getOriginalExchange()|record.getOriginalRoutingKey()",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:onDeleted:EXCHANGE|ROUTING_KEY_DELETED",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:onPublished:EXCHANGE|ROUTING_KEY",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:onUpdated:EXCHANGE|ROUTING_KEY_UPDATED",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java:publishOne:EXCHANGE|ROUTING_KEY_DELETED",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java:publishOne:EXCHANGE|ROUTING_KEY_PUBLISHED",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java:publishOne:EXCHANGE|ROUTING_KEY_SUMMARY_GENERATE",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java:publishOne:EXCHANGE|ROUTING_KEY_UPDATED",
            "nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/port/UserEventOutboxPort.java:publishOne:EXCHANGE|ROUTING_KEY_NICKNAME_CHANGED"
    );

    private static final Set<String> BEST_EFFORT_LISTENERS_FROM_INVENTORY = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java:onMessage"
    );

    private static final Set<String> MANUAL_ACK_LISTENERS_FROM_INVENTORY = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onFollow",
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onBlock"
    );
    private static final Set<String> METHOD_DECLARATION_EXCLUDED_NAMES = Set.of(
            "if", "for", "while", "switch", "catch", "try", "do", "synchronized"
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

    private static final Set<String> EXPECTED_LISTENER_INVENTORY = Set.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onBlock",
            "nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java:onFollow",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleDLQConsumer.java:onDLQ",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackAConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackADlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateConsumer.java:onPostSummaryGenerate",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationBlockDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationFollowDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanDlqConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcConsumer.java:onPostChanged",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java:onRaw",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java:onUserNicknameChanged"
    );

    private static final List<String> EXPECTED_RAW_PUBLISH_FINDING_KEYS = List.of();

    private static final List<String> EXPECTED_UNANNOTATED_LISTENER_FINDINGS = List.of(
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java:onMessage",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcConsumer.java:onPostChanged",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java:onRaw",
            "nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java:onUserNicknameChanged"
    );

    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:(?:public|private|protected)\\s+)?(?:static\\s+)?[^\\n=;]+?\\s+(\\w+)\\s*\\([^\\n;]*\\)\\s*(?:throws\\s+[^\\n{]+)?\\s*\\{?\\s*$",
            Pattern.MULTILINE);
    private static final Pattern CONVERT_AND_SEND_START = Pattern.compile("(?:\\w+\\.)?convertAndSend\\s*\\(");

    @Test
    void rawRabbitTemplatePublishesRemainAtCurrentAuditBaseline() throws IOException {
        List<RawPublishFinding> findings = javaSources().stream()
                .filter(path -> !isTestSource(path))
                .flatMap(path -> rawPublishFindings(path).stream())
                .filter(finding -> !RAW_PUBLISH_ALLOWED_KEYS.contains(finding.key()))
                .sorted()
                .toList();

        assertEquals(EXPECTED_RAW_PUBLISH_FINDING_KEYS, findings.stream().map(RawPublishFinding::key).toList(),
                () -> "Raw publish diagnostics:\n" + diagnostics(findings));
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

    @Test
    void rabbitListenersRemainCoveredByInventory() throws IOException {
        Set<String> listeners = new LinkedHashSet<>();
        for (Path path : javaSources()) {
            if (!isTestSource(path)) {
                listeners.addAll(rabbitListenerMethods(path).stream().map(ListenerMethod::id).toList());
            }
        }

        assertEquals(EXPECTED_LISTENER_INVENTORY, listeners);
    }

    @Test
    void reliableConsumeDetectionHandlesMultilineAnnotationBlocks() {
        String source = """
                class Example {
                    @ReliableMqConsume(
                            consumerName = "example",
                            eventId = "#event.eventId",
                            payload = "#event"
                    )
                    @RabbitListener(
                            queues = "example.queue",
                            containerFactory = "reliableMqListenerContainerFactory"
                    )
                    public void consume(Object event) {
                    }

                    @RabbitListener(queues = "other.queue")
                    @ReliableMqConsume(
                            consumerName = "other",
                            eventId = "#event.id",
                            payload = "#event"
                    )
                    public void consumeOther(Object event) {
                    }
                }
                """;

        List<ListenerMethod> methods = rabbitListenerMethods(Path.of("Example.java"), source);

        assertEquals(List.of(
                new ListenerMethod("nexus-app/Example.java", "consume", true),
                new ListenerMethod("nexus-app/Example.java", "consumeOther", true)
        ), methods);
    }

    @Test
    void rawPublishDetectionHandlesMultilineInvocationsAndPackagePrivateMethods() {
        String source = """
                class Example {
                    @RabbitListener(queues = "example.queue")
                    void packagePrivateListener(Object event) {
                    }

                    void publish(Object event) {
                        rabbitTemplate.convertAndSend(
                                EXCHANGE,
                                ROUTING_KEY,
                                event);
                    }
                }
                """;

        List<RawPublishFinding> rawPublishFindings = rawPublishFindings(Path.of("Example.java"), source);
        List<ListenerMethod> listenerMethods = rabbitListenerMethods(Path.of("Example.java"), source);

        assertEquals(List.of("nexus-app/Example.java:publish:EXCHANGE|ROUTING_KEY"),
                rawPublishFindings.stream().map(RawPublishFinding::key).toList());
        assertEquals(List.of(new ListenerMethod("nexus-app/Example.java", "packagePrivateListener", false)),
                listenerMethods);
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

    private static List<RawPublishFinding> rawPublishFindings(Path path) {
        return rawPublishFindings(path, read(path));
    }

    private static List<RawPublishFinding> rawPublishFindings(Path path, String source) {
        List<RawPublishFinding> findings = new ArrayList<>();
        Matcher matcher = CONVERT_AND_SEND_START.matcher(source);
        while (matcher.find()) {
            int openParen = source.indexOf('(', matcher.start());
            int closeParen = closeParenIndex(source, openParen);
            List<String> args = splitTopLevelArguments(source.substring(openParen + 1, closeParen));
            String target = args.size() >= 2 ? normalize(args.get(0)) + "|" + normalize(args.get(1)) : "unknown|unknown";
            findings.add(new RawPublishFinding(relative(path), enclosingMethod(source, matcher.start()), target,
                    lineNumber(source, matcher.start()), invocationDiagnostic(source, matcher.start(), closeParen)));
        }
        return findings;
    }

    private static List<String> unannotatedListenerFindings(Path path) {
        List<String> findings = new ArrayList<>();
        for (ListenerMethod listener : rabbitListenerMethods(path)) {
            String id = listener.id();
            if (listener.hasReliableMqConsume()
                    || BEST_EFFORT_LISTENERS_FROM_INVENTORY.contains(id)
                    || MANUAL_ACK_LISTENERS_FROM_INVENTORY.contains(id)) {
                continue;
            }
            findings.add(listener.id());
        }
        return findings;
    }

    private static List<ListenerMethod> rabbitListenerMethods(Path path) {
        return rabbitListenerMethods(path, read(path));
    }

    private static List<ListenerMethod> rabbitListenerMethods(Path path, String source) {
        Matcher matcher = METHOD_DECLARATION.matcher(source);
        List<ListenerMethod> listeners = new ArrayList<>();
        while (matcher.find()) {
            String annotationBlock = annotationBlockBefore(source, matcher.start());
            if (annotationBlock.contains("@RabbitListener")) {
                listeners.add(new ListenerMethod(relative(path), matcher.group(1),
                        annotationBlock.contains("@ReliableMqConsume")
                                || annotationBlock.contains("@cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume")));
            }
        }
        return listeners;
    }

    private static String annotationBlockBefore(String source, int methodStart) {
        int cursor = previousNonBlankLineEnd(source, methodStart);
        if (cursor < 0) {
            return "";
        }
        int blockStart = cursor + 1;
        boolean sawAnnotation = false;
        boolean sawCandidateLine = false;
        while (cursor >= 0) {
            int lineStart = source.lastIndexOf('\n', cursor);
            int start = lineStart < 0 ? 0 : lineStart + 1;
            String line = source.substring(start, cursor + 1).trim();
            if (line.startsWith("@")) {
                sawAnnotation = true;
                sawCandidateLine = true;
                blockStart = start;
                cursor = previousNonBlankLineEnd(source, start);
                continue;
            }
            if (isAnnotationContinuationLine(line)) {
                sawCandidateLine = true;
                blockStart = start;
                cursor = previousNonBlankLineEnd(source, start);
                continue;
            }
            if (sawCandidateLine && sawAnnotation) {
                break;
            }
            return "";
        }
        return sawAnnotation ? source.substring(blockStart, methodStart) : "";
    }

    private static boolean isAnnotationContinuationLine(String line) {
        return line.startsWith(")")
                || line.endsWith(",")
                || line.endsWith("(")
                || line.contains("=");
    }

    private static int previousNonBlankLineEnd(String source, int beforeOffset) {
        int cursor = Math.min(beforeOffset - 1, source.length() - 1);
        while (cursor >= 0) {
            int lineEnd = source.lastIndexOf('\n', cursor);
            int end = lineEnd < 0 ? cursor : lineEnd - 1;
            int start = source.lastIndexOf('\n', end);
            start = start < 0 ? 0 : start + 1;
            if (end >= start && !source.substring(start, end + 1).trim().isEmpty()) {
                return end;
            }
            cursor = start - 2;
        }
        return -1;
    }

    private static String enclosingMethod(String source, int offset) {
        Matcher matcher = METHOD_DECLARATION.matcher(source.substring(0, Math.min(offset, source.length())));
        String method = "<class>";
        while (matcher.find()) {
            if (!METHOD_DECLARATION_EXCLUDED_NAMES.contains(matcher.group(1))) {
                method = matcher.group(1);
            }
        }
        return method;
    }

    private static int closeParenIndex(String source, int openParen) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openParen; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return source.length() - 1;
    }

    private static List<String> splitTopLevelArguments(String rawArguments) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < rawArguments.length(); i++) {
            char ch = rawArguments.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                continue;
            }
            if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                continue;
            }
            if (ch == ',' && depth == 0) {
                args.add(rawArguments.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < rawArguments.length()) {
            args.add(rawArguments.substring(start).trim());
        }
        return args;
    }

    private static int lineNumber(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String invocationDiagnostic(String source, int start, int closeParen) {
        int end = Math.min(source.length(), closeParen + 2);
        return source.substring(start, end).trim().replaceAll("\\s+", " ");
    }

    private static String normalize(String expression) {
        return expression == null ? "unknown" : expression.replaceAll("\\s+", "");
    }

    private static String diagnostics(List<RawPublishFinding> findings) {
        StringBuilder builder = new StringBuilder();
        for (RawPublishFinding finding : findings) {
            builder.append(finding.file()).append(':').append(finding.lineNumber()).append(':')
                    .append(finding.line()).append(" -> ").append(finding.key()).append('\n');
        }
        return builder.toString();
    }

    private static boolean isTestSource(Path path) {
        return relative(path).contains("/src/test/");
    }

    private record RawPublishFinding(String file, String method, String target, int lineNumber, String line)
            implements Comparable<RawPublishFinding> {

        String key() {
            return file + ":" + method + ":" + target;
        }

        @Override
        public int compareTo(RawPublishFinding other) {
            return key().compareTo(other.key());
        }
    }

    private record ListenerMethod(String file, String method, boolean hasReliableMqConsume) {

        String id() {
            return file + ":" + method;
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = nexusRoot();
        if (!normalized.startsWith(root)) {
            return path.toString().replace('\\', '/');
        }
        return root.relativize(normalized).toString().replace('\\', '/');
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
