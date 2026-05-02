package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.producer.SearchIndexCdcEventProducer;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;

class SearchIndexCdcRawPublisherTest {

    @Test
    void onRaw_shouldPublishPostChangedEventsForAllowedTables() {
        SearchIndexCdcEventProducer producer = Mockito.mock(SearchIndexCdcEventProducer.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SearchIndexCdcRawPublisher publisher = new SearchIndexCdcRawPublisher(producer, objectMapper);

        ReflectionTestUtils.setField(publisher, "filterSchema", "nexus_social");
        ReflectionTestUtils.setField(publisher, "filterTables", "content_post,content_post_type");

        String raw = """
                {
                  "database": "nexus_social",
                  "table": "content_post",
                  "logfileName": "mysql-bin.000001",
                  "logfileOffset": 12345,
                  "tsMs": 1712345678901,
                  "data": [
                    {"post_id": 101},
                    {"post_id": 102}
                  ]
                }
                """;
        MessageProperties props = new MessageProperties();
        props.setMessageId("m-1");
        Message msg = new Message(raw.getBytes(StandardCharsets.UTF_8), props);

        publisher.onRaw(msg);

        ArgumentCaptor<PostChangedCdcEvent> cap = ArgumentCaptor.forClass(PostChangedCdcEvent.class);
        Mockito.verify(producer, times(2)).publish(cap.capture());
        assertEquals(2, cap.getAllValues().size());
        assertEquals(101L, cap.getAllValues().get(0).getPostId());
        assertEquals("mysql-bin.000001:12345:101", cap.getAllValues().get(0).getEventId());
        assertEquals(102L, cap.getAllValues().get(1).getPostId());
        assertEquals("mysql-bin.000001:12345:102", cap.getAllValues().get(1).getEventId());
    }
}
