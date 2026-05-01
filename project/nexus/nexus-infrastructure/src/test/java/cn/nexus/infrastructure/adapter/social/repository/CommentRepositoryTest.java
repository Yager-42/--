package cn.nexus.infrastructure.adapter.social.repository;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommentRepositoryTest {

    @Test
    void commentPersistenceModelShouldNotExposeCounterFields() {
        Set<String> forbidden = Set.of("likeCount", "replyCount", "liked");

        assertThat(Arrays.stream(CommentPO.class.getDeclaredFields())
                        .map(java.lang.reflect.Field::getName)
                        .filter(forbidden::contains)
                        .toList())
                .as("comment persistence fields")
                .isEmpty();
    }

    @Test
    void commentRepositoryAndMapperShouldNotReadOrWriteCounterColumns() throws Exception {
        assertNoCounterReference(Path.of("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java"));
        assertNoCounterReference(Path.of("nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml"));
    }

    private static void assertNoCounterReference(Path path) throws Exception {
        String source = Files.readString(projectRoot().resolve(path), StandardCharsets.UTF_8);
        assertThat(source)
                .as(path.toString())
                .doesNotContain("like_count")
                .doesNotContain("reply_count")
                .doesNotContain("likeCount")
                .doesNotContain("replyCount")
                .doesNotContain("liked");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (current.getFileName() != null && current.getFileName().toString().equals("nexus-infrastructure")) {
            return current.getParent();
        }
        return current;
    }
}
