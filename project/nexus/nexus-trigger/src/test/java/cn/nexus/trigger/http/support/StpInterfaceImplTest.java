package cn.nexus.trigger.http.support;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.auth.adapter.repository.IAuthRoleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StpInterfaceImplTest {

    @Test
    void getRoleList_shouldLoadRolesByUserId() {
        IAuthRoleRepository authRoleRepository = Mockito.mock(IAuthRoleRepository.class);
        StpInterfaceImpl stpInterface = new StpInterfaceImpl(authRoleRepository);
        when(authRoleRepository.listRoleCodes(1001L)).thenReturn(List.of("USER", "ADMIN"));

        assertIterableEquals(List.of("USER", "ADMIN"), stpInterface.getRoleList("1001", "login"));
    }

    @Test
    void getRoleList_withIllegalLoginId_shouldReturnEmptyList() {
        IAuthRoleRepository authRoleRepository = Mockito.mock(IAuthRoleRepository.class);
        StpInterfaceImpl stpInterface = new StpInterfaceImpl(authRoleRepository);

        assertTrue(stpInterface.getRoleList("oops", "login").isEmpty());
        verify(authRoleRepository, never()).listRoleCodes(Mockito.anyLong());
    }

    @Test
    void getPermissionList_shouldStayEmptyForCurrentStage() {
        IAuthRoleRepository authRoleRepository = Mockito.mock(IAuthRoleRepository.class);
        StpInterfaceImpl stpInterface = new StpInterfaceImpl(authRoleRepository);

        assertTrue(stpInterface.getPermissionList(1001L, "login").isEmpty());
    }
}
