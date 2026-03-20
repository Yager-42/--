package cn.nexus.infrastructure.adapter.user.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.dao.user.IUserEventOutboxDao;
import cn.nexus.infrastructure.dao.user.po.UserEventOutboxPO;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class UserEventOutboxPortTest {

    @Test
    void saveNicknameChanged_shouldInsertOutboxRecord() {
        IUserEventOutboxDao outboxDao = Mockito.mock(IUserEventOutboxDao.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        UserEventOutboxPort port = new UserEventOutboxPort(outboxDao, rabbitTemplate);

        port.saveNicknameChanged(10L, 20L);

        ArgumentCaptor<UserEventOutboxPO> captor = ArgumentCaptor.forClass(UserEventOutboxPO.class);
        verify(outboxDao).insertIgnore(captor.capture());
        UserEventOutboxPO po = captor.getValue();
        assertEquals("user.nickname_changed", po.getEventType());
        assertEquals("user.nickname_changed:10:20", po.getFingerprint());
        assertEquals("NEW", po.getStatus());
        assertEquals(0, po.getRetryCount());
        assertNotNull(po.getPayload());
        org.junit.jupiter.api.Assertions.assertTrue(po.getPayload().contains("\"userId\":10"));
        org.junit.jupiter.api.Assertions.assertTrue(po.getPayload().contains("\"tsMs\":20"));
    }

    @Test
    void saveNicknameChanged_shouldIgnoreInvalidInput() {
        IUserEventOutboxDao outboxDao = Mockito.mock(IUserEventOutboxDao.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        UserEventOutboxPort port = new UserEventOutboxPort(outboxDao, rabbitTemplate);

        port.saveNicknameChanged(null, 1L);
        port.saveNicknameChanged(1L, null);

        verify(outboxDao, never()).insertIgnore(Mockito.any());
    }

    @Test
    void tryPublishPending_shouldPublishNewAndFailRecords() {
        IUserEventOutboxDao outboxDao = Mockito.mock(IUserEventOutboxDao.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        UserEventOutboxPort port = new UserEventOutboxPort(outboxDao, rabbitTemplate);

        when(outboxDao.selectByStatus("NEW", 100)).thenReturn(List.of(po(1L, "NEW", "{\"userId\":11,\"tsMs\":101}")));
        when(outboxDao.selectByStatus("FAIL", 100)).thenReturn(List.of(po(2L, "FAIL", "{\"userId\":12,\"tsMs\":102}")));

        port.tryPublishPending();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, Mockito.times(2))
                .convertAndSend(Mockito.eq("social.feed"), Mockito.eq("user.nickname_changed"), eventCaptor.capture());
        List<Object> events = eventCaptor.getAllValues();
        assertEquals(11L, ((cn.nexus.types.event.UserNicknameChangedEvent) events.get(0)).getUserId());
        assertEquals(12L, ((cn.nexus.types.event.UserNicknameChangedEvent) events.get(1)).getUserId());
        verify(outboxDao).markDone(1L);
        verify(outboxDao).markDone(2L);
    }

    @Test
    void tryPublishPending_shouldMarkFailWhenPublishThrows() {
        IUserEventOutboxDao outboxDao = Mockito.mock(IUserEventOutboxDao.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        UserEventOutboxPort port = new UserEventOutboxPort(outboxDao, rabbitTemplate);

        when(outboxDao.selectByStatus("NEW", 100)).thenReturn(List.of(po(1L, "NEW", "{\"userId\":11,\"tsMs\":101}")));
        when(outboxDao.selectByStatus("FAIL", 100)).thenReturn(List.of());
        Mockito.doThrow(new RuntimeException("mq fail"))
                .when(rabbitTemplate)
                .convertAndSend(Mockito.eq("social.feed"), Mockito.eq("user.nickname_changed"), Mockito.any(Object.class));

        port.tryPublishPending();

        verify(outboxDao).markFail(1L);
        verify(outboxDao, never()).markDone(1L);
    }

    @Test
    void cleanDoneBefore_shouldHandleNullAndDelegate() {
        IUserEventOutboxDao outboxDao = Mockito.mock(IUserEventOutboxDao.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        UserEventOutboxPort port = new UserEventOutboxPort(outboxDao, rabbitTemplate);

        assertEquals(0, port.cleanDoneBefore(null));
        Date before = new Date(123L);
        when(outboxDao.deleteOlderThan(before, "DONE")).thenReturn(5);
        assertEquals(5, port.cleanDoneBefore(before));
    }

    private UserEventOutboxPO po(Long id, String status, String payload) {
        UserEventOutboxPO po = new UserEventOutboxPO();
        po.setId(id);
        po.setEventType("user.nickname_changed");
        po.setFingerprint("fp-" + id);
        po.setPayload(payload);
        po.setStatus(status);
        po.setRetryCount(0);
        return po;
    }
}
