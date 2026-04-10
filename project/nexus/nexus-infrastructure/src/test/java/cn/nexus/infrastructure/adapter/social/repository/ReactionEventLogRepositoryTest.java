package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.dao.social.IInteractionReactionEventLogDao;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ReactionEventLogRepositoryTest {

    @Test
    void append_shouldReturnInsertedOnFirstWrite() {
        IInteractionReactionEventLogDao dao = Mockito.mock(IInteractionReactionEventLogDao.class);
        ReactionEventLogRepository repository = new ReactionEventLogRepository(dao);
        when(dao.insertIgnore(Mockito.any())).thenReturn(1);

        String result = repository.append("evt-1", "POST", 101L, "LIKE", 7L, 1, 1, 123456L);

        assertEquals("inserted", result);
        ArgumentCaptor<InteractionReactionEventLogPO> captor = ArgumentCaptor.forClass(InteractionReactionEventLogPO.class);
        verify(dao).insertIgnore(captor.capture());
        InteractionReactionEventLogPO po = captor.getValue();
        assertEquals("evt-1", po.getEventId());
        assertEquals("POST", po.getTargetType());
        assertEquals(101L, po.getTargetId());
        assertEquals("LIKE", po.getReactionType());
        assertEquals(7L, po.getUserId());
        assertEquals(1, po.getDesiredState());
        assertEquals(1, po.getDelta());
        assertEquals(123456L, po.getEventTime());
    }

    @Test
    void append_shouldReturnDuplicateWhenEventIdAlreadyExists() {
        IInteractionReactionEventLogDao dao = Mockito.mock(IInteractionReactionEventLogDao.class);
        ReactionEventLogRepository repository = new ReactionEventLogRepository(dao);
        when(dao.insertIgnore(Mockito.any())).thenReturn(0);

        String result = repository.append("evt-1", "POST", 101L, "LIKE", 7L, 1, 1, 123456L);

        assertEquals("duplicate", result);
    }

    @Test
    void append_shouldSkipZeroDeltaEvents() {
        IInteractionReactionEventLogDao dao = Mockito.mock(IInteractionReactionEventLogDao.class);
        ReactionEventLogRepository repository = new ReactionEventLogRepository(dao);

        String result = repository.append("evt-0", "POST", 101L, "LIKE", 7L, 1, 0, 123456L);

        assertEquals("duplicate", result);
        verify(dao, never()).insertIgnore(Mockito.any());
    }

    @Test
    void pageAfterSeq_shouldReturnAscendingRowsWithPersistedFields() {
        IInteractionReactionEventLogDao dao = Mockito.mock(IInteractionReactionEventLogDao.class);
        ReactionEventLogRepository repository = new ReactionEventLogRepository(dao);
        when(dao.selectPage("POST", "LIKE", 20L, 2)).thenReturn(List.of(row(21L, "evt-21", 1, 1, 1111L), row(22L, "evt-22", 0, -1, 2222L)));

        List<InteractionReactionEventLogPO> result = repository.pageAfterSeq("POST", "LIKE", 20L, 2);

        assertEquals(2, result.size());
        assertEquals(21L, result.get(0).getSeq());
        assertEquals("evt-21", result.get(0).getEventId());
        assertEquals(1, result.get(0).getDesiredState());
        assertEquals(1, result.get(0).getDelta());
        assertEquals(1111L, result.get(0).getEventTime());
        assertEquals(22L, result.get(1).getSeq());
        assertEquals("evt-22", result.get(1).getEventId());
        assertEquals(0, result.get(1).getDesiredState());
        assertEquals(-1, result.get(1).getDelta());
        assertEquals(2222L, result.get(1).getEventTime());
        verify(dao).selectPage("POST", "LIKE", 20L, 2);
    }

    @Test
    void schemaFiles_shouldDeclareRequiredReactionEventLogIndexes() throws Exception {
        String socialSchema = java.nio.file.Files.readString(java.nio.file.Path.of("C:/Users/Administrator/Desktop/文档/project/nexus/docs/social_schema.sql"));
        String finalSchema = java.nio.file.Files.readString(java.nio.file.Path.of("C:/Users/Administrator/Desktop/文档/project/nexus/docs/nexus_final_mysql_schema.sql"));

        assertSchemaContains(socialSchema);
        assertSchemaContains(finalSchema);
    }

    private void assertSchemaContains(String schema) {
        String normalized = schema.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(normalized.contains("create table if not exists `interaction_reaction_event_log`"));
        assertTrue(normalized.contains("`seq` bigint"));
        assertTrue(normalized.contains("`event_id` varchar(128) not null"));
        assertTrue(normalized.contains("`desired_state` tinyint not null"));
        assertTrue(normalized.contains("`delta` tinyint not null"));
        assertTrue(normalized.contains("`event_time` bigint not null"));
        assertTrue(normalized.contains("unique key `uk_interaction_reaction_event_log_event_id` (`event_id`)"));
        assertTrue(normalized.contains("key `idx_reaction_event_log_target_seq` (`target_type`, `target_id`, `reaction_type`, `seq`)"));
        assertTrue(normalized.contains("key `idx_reaction_event_log_user_seq` (`user_id`, `seq`)"));
    }

    private InteractionReactionEventLogPO row(Long seq, String eventId, Integer desiredState, Integer delta, Long eventTime) {
        InteractionReactionEventLogPO po = new InteractionReactionEventLogPO();
        po.setSeq(seq);
        po.setEventId(eventId);
        po.setTargetType("POST");
        po.setTargetId(101L);
        po.setReactionType("LIKE");
        po.setUserId(7L);
        po.setDesiredState(desiredState);
        po.setDelta(delta);
        po.setEventTime(eventTime);
        return po;
    }
}
