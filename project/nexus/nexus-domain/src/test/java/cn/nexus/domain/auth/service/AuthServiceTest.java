package cn.nexus.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.auth.adapter.port.IAuthAdminBootstrapPort;
import cn.nexus.domain.auth.adapter.port.IAuthThrottlePort;
import cn.nexus.domain.auth.adapter.port.IPasswordHasher;
import cn.nexus.domain.auth.adapter.port.ISmsSenderPort;
import cn.nexus.domain.auth.adapter.repository.IAuthAccountRepository;
import cn.nexus.domain.auth.adapter.repository.IAuthRoleRepository;
import cn.nexus.domain.auth.adapter.repository.IAuthSmsCodeRepository;
import cn.nexus.domain.auth.adapter.repository.IAuthUserBaseRepository;
import cn.nexus.domain.auth.model.entity.AuthAccountEntity;
import cn.nexus.domain.auth.model.valobj.AuthAdminVO;
import cn.nexus.domain.auth.model.valobj.AuthLoginResultVO;
import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
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
@ContextConfiguration(classes = AuthServiceTest.TestConfig.class)
class AuthServiceTest {

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        IAuthAccountRepository authAccountRepository() {
            return Mockito.mock(IAuthAccountRepository.class);
        }

        @Bean
        IAuthRoleRepository authRoleRepository() {
            return Mockito.mock(IAuthRoleRepository.class);
        }

        @Bean
        IAuthSmsCodeRepository authSmsCodeRepository() {
            return Mockito.mock(IAuthSmsCodeRepository.class);
        }

        @Bean
        IAuthUserBaseRepository authUserBaseRepository() {
            return Mockito.mock(IAuthUserBaseRepository.class);
        }

        @Bean
        IAuthAdminBootstrapPort authAdminBootstrapPort() {
            return Mockito.mock(IAuthAdminBootstrapPort.class);
        }

        @Bean
        IPasswordHasher passwordHasher() {
            return Mockito.mock(IPasswordHasher.class);
        }

        @Bean
        ISmsSenderPort smsSenderPort() {
            return Mockito.mock(ISmsSenderPort.class);
        }

        @Bean
        IAuthThrottlePort authThrottlePort() {
            return Mockito.mock(IAuthThrottlePort.class);
        }

        @Bean
        IUserStatusRepository userStatusRepository() {
            return Mockito.mock(IUserStatusRepository.class);
        }

        @Bean
        ISocialIdPort socialIdPort() {
            return Mockito.mock(ISocialIdPort.class);
        }

        @Bean
        AuthService authService(IAuthAccountRepository authAccountRepository,
                                IAuthRoleRepository authRoleRepository,
                                IAuthSmsCodeRepository authSmsCodeRepository,
                                IAuthUserBaseRepository authUserBaseRepository,
                                IAuthAdminBootstrapPort authAdminBootstrapPort,
                                IPasswordHasher passwordHasher,
                                ISmsSenderPort smsSenderPort,
                                IAuthThrottlePort authThrottlePort,
                                IUserStatusRepository userStatusRepository,
                                ISocialIdPort socialIdPort) {
            return new AuthService(
                    authAccountRepository,
                    authRoleRepository,
                    authSmsCodeRepository,
                    authUserBaseRepository,
                    authAdminBootstrapPort,
                    passwordHasher,
                    smsSenderPort,
                    authThrottlePort,
                    userStatusRepository,
                    socialIdPort
            );
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
    private AuthService authService;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthAccountRepository authAccountRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthRoleRepository authRoleRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthSmsCodeRepository authSmsCodeRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthUserBaseRepository authUserBaseRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthAdminBootstrapPort authAdminBootstrapPort;
    @org.springframework.beans.factory.annotation.Autowired
    private IPasswordHasher passwordHasher;
    @org.springframework.beans.factory.annotation.Autowired
    private ISmsSenderPort smsSenderPort;
    @org.springframework.beans.factory.annotation.Autowired
    private IAuthThrottlePort authThrottlePort;
    @org.springframework.beans.factory.annotation.Autowired
    private IUserStatusRepository userStatusRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private ISocialIdPort socialIdPort;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(
                authAccountRepository,
                authRoleRepository,
                authSmsCodeRepository,
                authUserBaseRepository,
                authAdminBootstrapPort,
                passwordHasher,
                smsSenderPort,
                authThrottlePort,
                userStatusRepository,
                socialIdPort
        );
    }

    @Test
    void sendSms_shouldApplyRateLimitInvalidateOldCodeAndStoreLatestCode() {
        when(smsSenderPort.send("13800138000", "123456", AuthSmsBizTypeVO.REGISTER)).thenReturn(true);

        authService.sendSms("13800138000", AuthSmsBizTypeVO.REGISTER, "127.0.0.1");

        verify(authThrottlePort).checkSmsSendLimit("13800138000", "127.0.0.1");
        verify(authSmsCodeRepository).invalidateLatest("13800138000", AuthSmsBizTypeVO.REGISTER);
        verify(authSmsCodeRepository).saveLatest(Mockito.eq("13800138000"), Mockito.eq(AuthSmsBizTypeVO.REGISTER), Mockito.anyString(), Mockito.anyLong(), Mockito.eq("127.0.0.1"), Mockito.eq("SENT"));
        verify(authThrottlePort).onSmsSend("13800138000", "127.0.0.1");
    }

    @Test
    void sendSms_whenProviderFails_shouldKeepOldLatestCodeAndRecordFailedSend() {
        when(smsSenderPort.send("13800138000", "123456", AuthSmsBizTypeVO.LOGIN)).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> authService.sendSms("13800138000", AuthSmsBizTypeVO.LOGIN, "127.0.0.1"));

        assertEquals(ResponseCode.UN_ERROR.getCode(), ex.getCode());
        verify(authSmsCodeRepository, never()).invalidateLatest(Mockito.anyString(), Mockito.any());
        verify(authSmsCodeRepository).saveFailedAttempt(Mockito.eq("13800138000"), Mockito.eq(AuthSmsBizTypeVO.LOGIN), Mockito.anyString(), Mockito.anyLong(), Mockito.eq("127.0.0.1"));
    }

    @Test
    void register_shouldCreateAuthAccountUserBaseAndActiveStatus() {
        when(socialIdPort.nextId()).thenReturn(1001L);
        when(authAccountRepository.existsByPhone("13800138000")).thenReturn(false);
        when(authAdminBootstrapPort.shouldGrantAdmin("13800138000")).thenReturn(false);

        Long userId = authService.register("13800138000", "123456", "raw-password", "neo", "avatar");

        assertEquals(1001L, userId);
        verify(authSmsCodeRepository).requireLatestValid("13800138000", AuthSmsBizTypeVO.REGISTER, "123456");
        verify(authAccountRepository).create(Mockito.any(AuthAccountEntity.class));
        verify(authUserBaseRepository).create(1001L, "u1001", "neo", "avatar");
        verify(authRoleRepository).assignRole(1001L, "USER");
        verify(userStatusRepository).upsertStatus(1001L, "ACTIVE", null);
    }

    @Test
    void register_whenBootstrapPhoneMatched_shouldGrantAdminRole() {
        when(socialIdPort.nextId()).thenReturn(1001L);
        when(authAccountRepository.existsByPhone("13800138000")).thenReturn(false);
        when(authAdminBootstrapPort.shouldGrantAdmin("13800138000")).thenReturn(true);

        authService.register("13800138000", "123456", "raw-password", "neo", "avatar");

        verify(authRoleRepository).assignRole(1001L, "USER");
        verify(authRoleRepository).assignRole(1001L, "ADMIN");
    }

    @Test
    void passwordLogin_shouldRejectDeactivatedUser() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1001L)
                .phone("13800138000")
                .passwordHash("hash")
                .build();
        when(authAccountRepository.requireByPhone("13800138000")).thenReturn(account);
        when(userStatusRepository.getStatus(1001L)).thenReturn("DEACTIVATED");

        AppException ex = assertThrows(AppException.class,
                () -> authService.passwordLogin("13800138000", "raw-password"));

        assertEquals(ResponseCode.USER_DEACTIVATED.getCode(), ex.getCode());
    }

    @Test
    void passwordLogin_whenBootstrapPhoneMatched_shouldGrantAdminBeforeReturn() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1001L)
                .phone("13800138000")
                .passwordHash("hash")
                .build();
        when(authAccountRepository.requireByPhone("13800138000")).thenReturn(account);
        when(userStatusRepository.getStatus(1001L)).thenReturn("ACTIVE");
        when(passwordHasher.matches("raw-password", "hash")).thenReturn(true);
        when(authAdminBootstrapPort.shouldGrantAdmin("13800138000")).thenReturn(true);

        AuthLoginResultVO result = authService.passwordLogin("13800138000", "raw-password");

        assertEquals(1001L, result.getUserId());
        verify(authRoleRepository).assignRole(1001L, "ADMIN");
    }

    @Test
    void smsLogin_shouldUseLatestUnusedCodeForLoginBizType() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1002L)
                .phone("13800138001")
                .passwordHash("hash")
                .build();
        when(authAccountRepository.requireByPhone("13800138001")).thenReturn(account);
        when(userStatusRepository.getStatus(1002L)).thenReturn("ACTIVE");

        AuthLoginResultVO result = authService.smsLogin("13800138001", "888888");

        assertEquals(1002L, result.getUserId());
        assertEquals("13800138001", result.getPhone());
        verify(authSmsCodeRepository).requireLatestValid("13800138001", AuthSmsBizTypeVO.LOGIN, "888888");
        verify(authSmsCodeRepository).markUsed("13800138001", AuthSmsBizTypeVO.LOGIN, "888888");
    }

    @Test
    void changePassword_shouldReturnUserIdForSessionInvalidation() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1003L)
                .phone("13800138002")
                .passwordHash("old-hash")
                .build();
        when(authAccountRepository.requireByUserId(1003L)).thenReturn(account);
        when(userStatusRepository.getStatus(1003L)).thenReturn("ACTIVE");
        when(passwordHasher.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordHasher.hash("new-password")).thenReturn("new-hash");

        Long userId = authService.changePassword(1003L, "old-password", "new-password");

        assertEquals(1003L, userId);
        verify(authAccountRepository).updatePassword(Mockito.eq(1003L), Mockito.eq("new-hash"), Mockito.anyLong());
    }

    @Test
    void grantAdmin_shouldAssignAdminRoleToActiveUser() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1005L)
                .phone("13800138005")
                .build();
        when(authAccountRepository.requireByUserId(1005L)).thenReturn(account);
        when(userStatusRepository.getStatus(1005L)).thenReturn("ACTIVE");

        Long userId = authService.grantAdmin(1005L);

        assertEquals(1005L, userId);
        verify(authRoleRepository).assignRole(1005L, "ADMIN");
    }

    @Test
    void revokeAdmin_shouldRemoveAdminRoleFromActiveUser() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1006L)
                .phone("13800138006")
                .build();
        when(authAccountRepository.requireByUserId(1006L)).thenReturn(account);
        when(userStatusRepository.getStatus(1006L)).thenReturn("ACTIVE");

        Long userId = authService.revokeAdmin(1006L);

        assertEquals(1006L, userId);
        verify(authRoleRepository).removeRole(1006L, "ADMIN");
    }

    @Test
    void listAdminUserIds_shouldDelegateToRoleRepository() {
        when(authRoleRepository.listUserIdsByRoleCode("ADMIN")).thenReturn(java.util.List.of(1001L, 1002L));

        java.util.List<Long> userIds = authService.listAdminUserIds();

        assertEquals(java.util.List.of(1001L, 1002L), userIds);
        verify(authRoleRepository).listUserIdsByRoleCode("ADMIN");
    }

    @Test
    void listAdmins_shouldAssemblePhoneStatusAndProfile() {
        when(authRoleRepository.listUserIdsByRoleCode("ADMIN")).thenReturn(java.util.List.of(1001L));
        when(authAccountRepository.listByUserIds(java.util.List.of(1001L))).thenReturn(java.util.List.of(
                AuthAccountEntity.builder().userId(1001L).phone("13800138000").build()
        ));
        when(authUserBaseRepository.listByUserIds(java.util.List.of(1001L))).thenReturn(java.util.List.of(
                AuthMeVO.builder().userId(1001L).nickname("neo").avatarUrl("a").build()
        ));
        when(userStatusRepository.getStatus(1001L)).thenReturn("ACTIVE");

        java.util.List<AuthAdminVO> admins = authService.listAdmins();

        assertEquals(1, admins.size());
        assertEquals(1001L, admins.get(0).getUserId());
        assertEquals("13800138000", admins.get(0).getPhone());
        assertEquals("ACTIVE", admins.get(0).getStatus());
        assertEquals("neo", admins.get(0).getNickname());
    }

    @Test
    void me_shouldMergeAccountProfileAndStatus() {
        AuthAccountEntity account = AuthAccountEntity.builder()
                .userId(1004L)
                .phone("13800138003")
                .build();
        when(authAccountRepository.requireByUserId(1004L)).thenReturn(account);
        when(userStatusRepository.getStatus(1004L)).thenReturn("ACTIVE");
        when(authUserBaseRepository.getMe(1004L)).thenReturn(AuthMeVO.builder()
                .userId(1004L)
                .nickname("neo")
                .avatarUrl("a")
                .build());

        AuthMeVO me = authService.me(1004L);

        assertEquals(1004L, me.getUserId());
        assertEquals("13800138003", me.getPhone());
        assertEquals("ACTIVE", me.getStatus());
        assertEquals("neo", me.getNickname());
    }
}
