package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.nexus.types.exception.AppException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostContentKvRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void postContentKv_shouldPersistReadBatchReadAndDeleteThroughCassandra() {
        String firstUuid = uniqueUuid();
        String secondUuid = uniqueUuid();

        postContentKvPort.add(firstUuid, "第一段真实 Cassandra 正文");
        postContentKvPort.add(secondUuid, "第二段真实 Cassandra 正文");

        assertThat(postContentKvPort.find(firstUuid)).isEqualTo("第一段真实 Cassandra 正文");
        assertThat(postContentKvPort.find(secondUuid)).isEqualTo("第二段真实 Cassandra 正文");

        Map<String, String> batch = postContentKvPort.findBatch(List.of(firstUuid, "bad-uuid", secondUuid));

        assertThat(batch)
                .containsEntry(firstUuid, "第一段真实 Cassandra 正文")
                .containsEntry(secondUuid, "第二段真实 Cassandra 正文");

        postContentKvPort.delete(firstUuid);

        assertThatThrownBy(() -> postContentKvPort.find(firstUuid)).isInstanceOf(AppException.class);
        assertThat(postContentKvPort.find(secondUuid)).isEqualTo("第二段真实 Cassandra 正文");
    }
}
