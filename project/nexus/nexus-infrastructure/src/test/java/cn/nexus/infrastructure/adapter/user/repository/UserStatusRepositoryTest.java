package cn.nexus.infrastructure.adapter.user.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.dao.user.IUserStatusDao;
import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserStatusRepositoryTest {

    @Test
    void getStatus_shouldDefaultToActive() {
        IUserStatusDao userStatusDao = Mockito.mock(IUserStatusDao.class);
        UserStatusRepository repository = new UserStatusRepository(userStatusDao);

        assertEquals("ACTIVE", repository.getStatus(null));
        when(userStatusDao.selectByUserId(1L)).thenReturn(null);
        assertEquals("ACTIVE", repository.getStatus(1L));

        UserStatusPO blank = new UserStatusPO();
        blank.setStatus(" ");
        when(userStatusDao.selectByUserId(2L)).thenReturn(blank);
        assertEquals("ACTIVE", repository.getStatus(2L));

        UserStatusPO po = new UserStatusPO();
        po.setStatus("DEACTIVATED");
        when(userStatusDao.selectByUserId(3L)).thenReturn(po);
        assertEquals("DEACTIVATED", repository.getStatus(3L));
    }

    @Test
    void upsertStatus_shouldIgnoreInvalidInputAndConvertDate() {
        IUserStatusDao userStatusDao = Mockito.mock(IUserStatusDao.class);
        UserStatusRepository repository = new UserStatusRepository(userStatusDao);

        repository.upsertStatus(null, "ACTIVE", null);
        repository.upsertStatus(1L, " ", null);
        verify(userStatusDao, never()).upsert(Mockito.any());

        repository.upsertStatus(2L, "DEACTIVATED", 12345L);
        ArgumentCaptor<UserStatusPO> firstCaptor = ArgumentCaptor.forClass(UserStatusPO.class);
        verify(userStatusDao).upsert(firstCaptor.capture());
        assertEquals(2L, firstCaptor.getValue().getUserId());
        assertEquals("DEACTIVATED", firstCaptor.getValue().getStatus());
        assertEquals(new Date(12345L), firstCaptor.getValue().getDeactivatedTime());

        repository.upsertStatus(3L, "ACTIVE", null);
        ArgumentCaptor<UserStatusPO> secondCaptor = ArgumentCaptor.forClass(UserStatusPO.class);
        verify(userStatusDao, Mockito.times(2)).upsert(secondCaptor.capture());
        assertNull(secondCaptor.getAllValues().get(1).getDeactivatedTime());
    }
}
