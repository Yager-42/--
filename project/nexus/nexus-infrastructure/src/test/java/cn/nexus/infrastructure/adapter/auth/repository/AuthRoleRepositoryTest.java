package cn.nexus.infrastructure.adapter.auth.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.auth.IAuthRoleDao;
import cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao;
import cn.nexus.infrastructure.dao.auth.po.AuthRolePO;
import cn.nexus.infrastructure.dao.auth.po.AuthUserRolePO;
import cn.nexus.types.exception.AppException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthRoleRepositoryTest {

    @Test
    void assignRole_shouldResolveRoleAndInsertIgnore() {
        IAuthRoleDao authRoleDao = Mockito.mock(IAuthRoleDao.class);
        IAuthUserRoleDao authUserRoleDao = Mockito.mock(IAuthUserRoleDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthRoleRepository repository = new AuthRoleRepository(authRoleDao, authUserRoleDao, socialIdPort);

        AuthRolePO rolePO = new AuthRolePO();
        rolePO.setRoleId(2L);
        rolePO.setRoleCode("ADMIN");
        when(authRoleDao.selectByRoleCode("ADMIN")).thenReturn(rolePO);
        when(socialIdPort.nextId()).thenReturn(9001L);

        repository.assignRole(1001L, "ADMIN");

        ArgumentCaptor<AuthUserRolePO> captor = ArgumentCaptor.forClass(AuthUserRolePO.class);
        verify(authUserRoleDao).insertIgnore(captor.capture());
        assertEquals(9001L, captor.getValue().getId());
        assertEquals(1001L, captor.getValue().getUserId());
        assertEquals(2L, captor.getValue().getRoleId());
    }

    @Test
    void assignRole_whenRoleMissing_shouldFailFast() {
        IAuthRoleDao authRoleDao = Mockito.mock(IAuthRoleDao.class);
        IAuthUserRoleDao authUserRoleDao = Mockito.mock(IAuthUserRoleDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthRoleRepository repository = new AuthRoleRepository(authRoleDao, authUserRoleDao, socialIdPort);

        when(authRoleDao.selectByRoleCode("ADMIN")).thenReturn(null);

        assertThrows(AppException.class, () -> repository.assignRole(1001L, "ADMIN"));
    }

    @Test
    void listRoleCodes_shouldReturnDaoResult() {
        IAuthRoleDao authRoleDao = Mockito.mock(IAuthRoleDao.class);
        IAuthUserRoleDao authUserRoleDao = Mockito.mock(IAuthUserRoleDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthRoleRepository repository = new AuthRoleRepository(authRoleDao, authUserRoleDao, socialIdPort);

        when(authUserRoleDao.selectRoleCodesByUserId(1002L)).thenReturn(List.of("USER", "ADMIN"));

        assertIterableEquals(List.of("USER", "ADMIN"), repository.listRoleCodes(1002L));
    }

    @Test
    void removeRole_shouldDelegateToDao() {
        IAuthRoleDao authRoleDao = Mockito.mock(IAuthRoleDao.class);
        IAuthUserRoleDao authUserRoleDao = Mockito.mock(IAuthUserRoleDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthRoleRepository repository = new AuthRoleRepository(authRoleDao, authUserRoleDao, socialIdPort);

        repository.removeRole(1003L, "ADMIN");

        verify(authUserRoleDao).deleteByUserIdAndRoleCode(1003L, "ADMIN");
    }

    @Test
    void listUserIdsByRoleCode_shouldReturnDaoResult() {
        IAuthRoleDao authRoleDao = Mockito.mock(IAuthRoleDao.class);
        IAuthUserRoleDao authUserRoleDao = Mockito.mock(IAuthUserRoleDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        AuthRoleRepository repository = new AuthRoleRepository(authRoleDao, authUserRoleDao, socialIdPort);

        when(authUserRoleDao.selectUserIdsByRoleCode("ADMIN")).thenReturn(List.of(1001L, 1002L));

        assertIterableEquals(List.of(1001L, 1002L), repository.listUserIdsByRoleCode("ADMIN"));
    }
}
