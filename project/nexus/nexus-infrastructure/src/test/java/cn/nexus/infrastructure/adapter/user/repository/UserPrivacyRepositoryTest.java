package cn.nexus.infrastructure.adapter.user.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.dao.social.IUserPrivacyDao;
import cn.nexus.infrastructure.dao.social.po.UserPrivacyPO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserPrivacyRepositoryTest {

    @Test
    void getNeedApproval_shouldDefaultToFalse() {
        IUserPrivacyDao userPrivacyDao = Mockito.mock(IUserPrivacyDao.class);
        UserPrivacyRepository repository = new UserPrivacyRepository(userPrivacyDao);

        assertEquals(false, repository.getNeedApproval(null));
        when(userPrivacyDao.selectByUserId(1L)).thenReturn(null);
        assertEquals(false, repository.getNeedApproval(1L));

        UserPrivacyPO po = new UserPrivacyPO();
        po.setNeedApproval(true);
        when(userPrivacyDao.selectByUserId(2L)).thenReturn(po);
        assertEquals(true, repository.getNeedApproval(2L));
    }

    @Test
    void upsertNeedApproval_shouldIgnoreInvalidInputAndDelegateValidInput() {
        IUserPrivacyDao userPrivacyDao = Mockito.mock(IUserPrivacyDao.class);
        UserPrivacyRepository repository = new UserPrivacyRepository(userPrivacyDao);

        repository.upsertNeedApproval(null, true);
        repository.upsertNeedApproval(1L, null);
        verify(userPrivacyDao, never()).upsertNeedApproval(Mockito.any(), Mockito.any());

        repository.upsertNeedApproval(2L, false);
        verify(userPrivacyDao).upsertNeedApproval(2L, false);
    }
}
