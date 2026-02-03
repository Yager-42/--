package cn.nexus.domain.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import cn.nexus.domain.user.adapter.repository.IUserPrivacyRepository;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.model.valobj.UserInternalUpsertRequestVO;
import cn.nexus.domain.user.model.valobj.UserProfilePatchVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserServiceTest.TestConfig.class)
class UserServiceTest {

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        IUserProfileRepository userProfileRepository() {
            return Mockito.mock(IUserProfileRepository.class);
        }

        @Bean
        IUserPrivacyRepository userPrivacyRepository() {
            return Mockito.mock(IUserPrivacyRepository.class);
        }

        @Bean
        IUserStatusRepository userStatusRepository() {
            return Mockito.mock(IUserStatusRepository.class);
        }

        @Bean
        IUserEventOutboxPort userEventOutboxPort() {
            return Mockito.mock(IUserEventOutboxPort.class);
        }

        @Bean
        UserService userService(IUserProfileRepository userProfileRepository,
                                IUserPrivacyRepository userPrivacyRepository,
                                IUserStatusRepository userStatusRepository,
                                IUserEventOutboxPort userEventOutboxPort) {
            return new UserService(userProfileRepository, userPrivacyRepository, userStatusRepository, userEventOutboxPort);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private UserService userService;
    @org.springframework.beans.factory.annotation.Autowired
    private IUserProfileRepository userProfileRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IUserPrivacyRepository userPrivacyRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IUserStatusRepository userStatusRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IUserEventOutboxPort userEventOutboxPort;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(userProfileRepository, userPrivacyRepository, userStatusRepository, userEventOutboxPort);
    }

    @Test
    void updateMyProfile_nicknameChanged_shouldSaveOutboxAndPublishAfterCommit() {
        Long userId = 1L;
        when(userStatusRepository.getStatus(userId)).thenReturn(UserService.STATUS_ACTIVE);
        when(userProfileRepository.get(userId)).thenReturn(UserProfileVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("old")
                .avatarUrl("a1")
                .build());
        when(userProfileRepository.updatePatch(userId, "new", null)).thenReturn(true);

        userService.updateMyProfile(userId, UserProfilePatchVO.builder().nickname("new").build());

        verify(userEventOutboxPort).saveNicknameChanged(org.mockito.ArgumentMatchers.eq(userId), anyLong());
        verify(userEventOutboxPort).tryPublishPending();
    }

    @Test
    void updateMyProfile_nicknameUnchanged_shouldNotPublish() {
        Long userId = 1L;
        when(userStatusRepository.getStatus(userId)).thenReturn(UserService.STATUS_ACTIVE);
        when(userProfileRepository.get(userId)).thenReturn(UserProfileVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("same")
                .avatarUrl("a1")
                .build());
        when(userProfileRepository.updatePatch(userId, "same", null)).thenReturn(true);

        userService.updateMyProfile(userId, UserProfilePatchVO.builder().nickname("same").build());

        verify(userEventOutboxPort, never()).saveNicknameChanged(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(userEventOutboxPort, never()).tryPublishPending();
    }

    @Test
    void updateMyProfile_deactivated_shouldThrow() {
        Long userId = 1L;
        when(userStatusRepository.getStatus(userId)).thenReturn(UserService.STATUS_DEACTIVATED);

        AppException ex = assertThrows(AppException.class,
                () -> userService.updateMyProfile(userId, UserProfilePatchVO.builder().nickname("new").build()));
        assertEquals(ResponseCode.USER_DEACTIVATED.getCode(), ex.getCode());
        verify(userProfileRepository, never()).updatePatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void internalUpsert_notFound_shouldThrow() {
        Long userId = 1L;
        when(userProfileRepository.get(userId)).thenReturn(null);

        UserInternalUpsertRequestVO req = UserInternalUpsertRequestVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("n1")
                .build();

        AppException ex = assertThrows(AppException.class, () -> userService.internalUpsert(req));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
        verify(userProfileRepository, never()).updatePatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void internalUpsert_usernameConflict_shouldThrow() {
        Long userId = 1L;
        when(userProfileRepository.get(userId)).thenReturn(UserProfileVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("n0")
                .avatarUrl("")
                .build());

        UserInternalUpsertRequestVO req = UserInternalUpsertRequestVO.builder()
                .userId(userId)
                .username("u2")
                .nickname("n1")
                .build();

        AppException ex = assertThrows(AppException.class, () -> userService.internalUpsert(req));
        assertEquals(ResponseCode.CONFLICT.getCode(), ex.getCode());
        verify(userProfileRepository, never()).updatePatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void internalUpsert_nicknameChanged_shouldSaveOutboxAndPublishAfterCommit() {
        Long userId = 1L;
        when(userProfileRepository.get(userId)).thenReturn(UserProfileVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("old")
                .avatarUrl("")
                .build());
        when(userProfileRepository.updatePatch(userId, "new", null)).thenReturn(true);

        UserInternalUpsertRequestVO req = UserInternalUpsertRequestVO.builder()
                .userId(userId)
                .username("u1")
                .nickname("new")
                .build();

        userService.internalUpsert(req);

        verify(userEventOutboxPort).saveNicknameChanged(org.mockito.ArgumentMatchers.eq(userId), anyLong());
        verify(userEventOutboxPort).tryPublishPending();
        verify(userPrivacyRepository, never()).upsertNeedApproval(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(userStatusRepository, never()).upsertStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
