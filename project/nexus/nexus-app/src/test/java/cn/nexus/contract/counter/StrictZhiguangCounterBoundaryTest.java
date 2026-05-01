package cn.nexus.contract.counter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class StrictZhiguangCounterBoundaryTest {

    private static final Path ROOT = projectRoot();

    private static final List<Path> ACTIVE_PATHS = List.of(
            Path.of("nexus-api/src/main/java"),
            Path.of("nexus-domain/src/main/java"),
            Path.of("nexus-infrastructure/src/main/java"),
            Path.of("nexus-infrastructure/src/main/resources/mapper"),
            Path.of("nexus-trigger/src/main/java"),
            Path.of("nexus-app/src/main/java"),
            Path.of("nexus-app/src/main/resources"),
            Path.of("docs/nexus_final_mysql_schema.sql"),
            Path.of("docs/social_schema.sql"),
            Path.of("docs/frontend-api.md"),
            Path.of("Dockerfile"));

    private static final List<ForbiddenPattern> FORBIDDEN_PATTERNS = List.of(
            ForbiddenPattern.literal("post_counter_projection"),
            ForbiddenPattern.literal("user_counter_repair_outbox"),
            ForbiddenPattern.literal("/interact/reaction"),
            ForbiddenPattern.literal("CommentLikeChangedConsumer"),
            ForbiddenPattern.literal("IReactionCommentLikeChangedMqPort"),
            ForbiddenPattern.literal("ReactionCommentLikeChangedMqPort"),
            ForbiddenPattern.literal("count-redis-module"),
            ForbiddenPattern.literal("interaction_reaction_event_log"),
            ForbiddenPattern.literal("ReactionEventLog"),
            ForbiddenPattern.literal("likeBitmapShardIndex"),
            ForbiddenPattern.regex("bm:like:post:[^\\s\"']+:idx"),
            ForbiddenPattern.regex("agg:v1:post:[^\\s\"']+:[0-9]+"),
            ForbiddenPattern.literal("cnt:v1:comment"),
            ForbiddenPattern.literal("bm:like:comment"),
            ForbiddenPattern.literal("bm:fav:comment"),
            ForbiddenPattern.literal("COMMENT_LIKED"),
            ForbiddenPattern.literal("like_count"),
            ForbiddenPattern.literal("reply_count"));

    private static final Pattern COMMENT_TARGET_COUNTER_CONTEXT = Pattern.compile(
            "counter|Counter|like|Like|fav|Fav|aggregation|Aggregation");

    @Test
    void activeCounterBoundariesMustNotExposeRemovedNexusCounterBehavior() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : filesToScan()) {
            collectViolations(file, violations);
        }

        assertThat(violations)
                .as("strict zhiguang counter boundary violations")
                .isEmpty();
    }

    private static List<Path> filesToScan() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path activePath : ACTIVE_PATHS) {
            Path resolved = ROOT.resolve(activePath).normalize();
            if (Files.isRegularFile(resolved)) {
                files.add(resolved);
            } else if (Files.isDirectory(resolved)) {
                try (Stream<Path> stream = Files.walk(resolved)) {
                    stream.filter(Files::isRegularFile)
                            .filter(StrictZhiguangCounterBoundaryTest::isScannableSourceFile)
                            .forEach(files::add);
                }
            }
        }

        try (Stream<Path> stream = Files.walk(ROOT)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(StrictZhiguangCounterBoundaryTest::isNotBuildOutput)
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean isScannableSourceFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".sql")
                || fileName.endsWith(".md")
                || fileName.equals("Dockerfile");
    }

    private static boolean isNotBuildOutput(Path path) {
        return StreamSupport.stream(ROOT.relativize(path).spliterator(), false)
                .map(Path::toString)
                .noneMatch(segment -> segment.equals("target") || segment.equals(".git"));
    }

    private static void collectViolations(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            for (ForbiddenPattern forbiddenPattern : FORBIDDEN_PATTERNS) {
                if (forbiddenPattern.matches(line)) {
                    violations.add(formatViolation(file, lineNumber, forbiddenPattern.label()));
                }
            }
            if (line.contains("ReactionTargetTypeEnumVO.COMMENT")
                    && COMMENT_TARGET_COUNTER_CONTEXT.matcher(line).find()) {
                violations.add(formatViolation(
                        file,
                        lineNumber,
                        "ReactionTargetTypeEnumVO.COMMENT in counter-like context"));
            }
        }
    }

    private static String formatViolation(Path file, int lineNumber, String label) {
        return ROOT.relativize(file) + ":" + lineNumber + " -> " + label;
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (current.getFileName() != null && current.getFileName().toString().equals("nexus-app")) {
            return current.getParent();
        }
        return current;
    }

    private record ForbiddenPattern(String label, Pattern pattern) {

        private static ForbiddenPattern literal(String value) {
            return new ForbiddenPattern(value, Pattern.compile(Pattern.quote(value)));
        }

        private static ForbiddenPattern regex(String value) {
            return new ForbiddenPattern(value, Pattern.compile(value));
        }

        private boolean matches(String line) {
            return pattern.matcher(line).find();
        }
    }
}
