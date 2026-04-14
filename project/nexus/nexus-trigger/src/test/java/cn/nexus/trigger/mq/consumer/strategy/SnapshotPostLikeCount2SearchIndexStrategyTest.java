package cn.nexus.trigger.mq.consumer.strategy;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.interaction.ReactionCountSnapshotEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class SnapshotPostLikeCount2SearchIndexStrategyTest {

    @Test
    void handle_shouldUpsertOnlyLatestPostLikeSnapshots() throws Exception {
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SnapshotPostLikeCount2SearchIndexStrategy strategy =
                new SnapshotPostLikeCount2SearchIndexStrategy(new ObjectMapper(), upsertService);

        ReactionCountSnapshotEvent post1 = snapshot("POST", 101L, "LIKE", 1L);
        ReactionCountSnapshotEvent ignoredUser = snapshot("USER", 8L, "LIKE", 3L);
        ReactionCountSnapshotEvent post2Old = snapshot("POST", 202L, "LIKE", 4L);
        ReactionCountSnapshotEvent post2New = snapshot("POST", 202L, "LIKE", 7L);
        ReactionCountSnapshotEvent ignoredFavorite = snapshot("POST", 303L, "FAVORITE", 2L);

        ObjectMapper mapper = new ObjectMapper();
        Message batch = new Message(mapper.writeValueAsBytes(List.of(post1, ignoredUser, post2Old)), new MessageProperties());
        Message single = new Message(mapper.writeValueAsBytes(post2New), new MessageProperties());
        Message other = new Message(mapper.writeValueAsBytes(ignoredFavorite), new MessageProperties());

        strategy.handle(List.of(batch, single, other));

        verify(upsertService).upsertPost(101L, 1L);
        verify(upsertService).upsertPost(202L, 7L);
        verify(upsertService, never()).upsertPost(eq(303L), eq(2L));
    }

    @Test
    void handle_shouldIgnoreInvalidMessages() {
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SnapshotPostLikeCount2SearchIndexStrategy strategy =
                new SnapshotPostLikeCount2SearchIndexStrategy(new ObjectMapper(), upsertService);

        Message invalid = new Message("not-json".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        Message blank = new Message(" ".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        strategy.handle(List.of(invalid, blank));

        verify(upsertService, never()).upsertPost(Mockito.anyLong(), Mockito.anyLong());
    }

    private ReactionCountSnapshotEvent snapshot(String targetType, Long targetId, String reactionType, Long count) {
        ReactionCountSnapshotEvent event = new ReactionCountSnapshotEvent();
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setReactionType(reactionType);
        event.setCount(count);
        return event;
    }
}
