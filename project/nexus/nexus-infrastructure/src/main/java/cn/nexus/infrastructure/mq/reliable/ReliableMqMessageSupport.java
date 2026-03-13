package cn.nexus.infrastructure.mq.reliable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * 可靠消息公共辅助：统一做 payload 序列化、eventId 提取与 DLQ message 解析。
 */
public class ReliableMqMessageSupport {

    private static final String TYPE_ID_HEADER = "__TypeId__";

    private final ObjectMapper objectMapper;

    public ReliableMqMessageSupport(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String toPayloadJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("serialize mq payload failed", e);
        }
    }

    public Object fromPayload(String payloadType, String payloadJson) {
        try {
            Class<?> clazz = Class.forName(payloadType);
            return objectMapper.readValue(payloadJson, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize mq payload failed type=" + payloadType, e);
        }
    }

    public String payloadType(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        return payload.getClass().getName();
    }

    public String extractEventId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode eventId = root == null ? null : root.get("eventId");
            return eventId == null || eventId.isNull() ? null : eventId.asText();
        } catch (Exception e) {
            return null;
        }
    }

    public String payloadJson(Message message) {
        if (message == null || message.getBody() == null) {
            return null;
        }
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    public String payloadType(Message message, String fallbackPayloadType) {
        if (message == null) {
            return fallbackPayloadType;
        }
        MessageProperties properties = message.getMessageProperties();
        if (properties == null) {
            return fallbackPayloadType;
        }
        Object header = properties.getHeaders().get(TYPE_ID_HEADER);
        if (header instanceof String headerValue && !headerValue.isBlank()) {
            return headerValue;
        }
        return fallbackPayloadType;
    }
}
