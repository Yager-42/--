package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.trigger.mq.producer.SearchIndexCdcEventProducer;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Canal(raw) -> Rabbit(postId 事件) 的“翻译器”。
 *
 * <p>说明：这里不直接驱动 ES，而是输出稳定的 {@link PostChangedCdcEvent}，让下游 consumer
 * 只依赖“postId 事件协议”，不依赖 Canal 的 raw 格式。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexCdcRawPublisher {

    private final SearchIndexCdcEventProducer searchIndexCdcEventProducer;
    private final ObjectMapper objectMapper;

    @Value("${search.index.cdc.filter.schema:nexus_social}")
    private String filterSchema;

    @Value("${search.index.cdc.filter.tables:content_post,content_post_type}")
    private String filterTables;

    @RabbitListener(queues = "${search.index.cdc.raw.queue}", containerFactory = "reliableMqListenerContainerFactory")
    public void onRaw(Message message) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            return;
        }
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("cdc raw not json", e);
        }

        String schema = textAny(root, "database", "schema", "db");
        if (schema == null || filterSchema == null || !schema.equalsIgnoreCase(filterSchema)) {
            return;
        }
        String table = textAny(root, "table");
        if (table == null || !allowedTables().contains(table)) {
            return;
        }

        long tsMs = tsMs(root);
        String prefix = prefix(root, message, raw);

        LinkedHashSet<Long> postIds = extractPostIds(root);
        if (postIds.isEmpty()) {
            return;
        }

        int published = 0;
        for (Long postId : postIds) {
            if (postId == null) {
                continue;
            }
            PostChangedCdcEvent event = new PostChangedCdcEvent();
            event.setSource("canal");
            event.setTable(table);
            event.setTsMs(tsMs > 0 ? tsMs : null);
            event.setPostId(postId);
            event.setEventId(prefix + ":" + postId);

            searchIndexCdcEventProducer.publish(event);
            published++;
        }

        log.info("event=search.index.cdc.publisher schema={} table={} postCount={} published={}",
                schema, table, postIds.size(), published);
    }

    private Set<String> allowedTables() {
        Set<String> set = new HashSet<>();
        if (filterTables == null || filterTables.isBlank()) {
            return set;
        }
        for (String s : filterTables.split(",")) {
            if (s == null) {
                continue;
            }
            String v = s.trim();
            if (!v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }

    private LinkedHashSet<Long> extractPostIds(JsonNode root) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return ids;
        }
        for (JsonNode row : data) {
            Long postId = longAny(row, "post_id", "postId");
            if (postId != null) {
                ids.add(postId);
            }
        }
        return ids;
    }

    private long tsMs(JsonNode root) {
        Long v = longAny(root, "tsMs", "ts", "es");
        if (v == null) {
            return -1L;
        }
        long val = v;
        if (val > 0 && val < 1_000_000_000_000L) {
            // 10 位秒级时间戳兜底
            return val * 1000L;
        }
        return val;
    }

    private String prefix(JsonNode root, Message message, String raw) {
        String file = textAny(root, "logfileName", "binlogFile", "binlogFilename");
        Long pos = longAny(root, "logfileOffset", "binlogOffset", "offset");
        if (file != null && pos != null) {
            return file + ":" + pos;
        }
        String id = textAny(root, "id");
        if (id != null && !id.isBlank()) {
            return "id:" + id.trim();
        }
        String msgId = message == null || message.getMessageProperties() == null ? null : message.getMessageProperties().getMessageId();
        if (msgId != null && !msgId.isBlank()) {
            return "msg:" + msgId.trim();
        }
        return "sha256:" + sha256Hex(raw);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private String textAny(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        return Arrays.stream(fields)
                .map(f -> node.get(f))
                .filter(v -> v != null && !v.isNull())
                .map(JsonNode::asText)
                .map(s -> s == null || s.isBlank() ? null : s)
                .findFirst()
                .orElse(null);
    }

    private Long longAny(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isNumber()) {
                return v.asLong();
            }
            String s = v.asText();
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                return Long.parseLong(s);
            } catch (Exception ignore) {
                // ignore
            }
        }
        return null;
    }
}
