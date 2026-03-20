package cn.nexus.domain.auth.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证领域服务。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEACTIVATED = "DEACTIVATED";
    private static final String LOGIN_TYPE_PASSWORD = "password";
    private static final String LOGIN_TYPE_SMS = "sms";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String SMS_SEND_STATUS_SENT = "SENT";
    private static final long SMS_EXPIRE_MS = 5 * 60 * 1000L;

    private final IAuthAccountRepository authAccountRepository;
    private final IAuthRoleRepository authRoleRepository;
    private final IAuthSmsCodeRepository authSmsCodeRepository;
    private final IAuthUserBaseRepository authUserBaseRepository;
    private final IAuthAdminBootstrapPort authAdminBootstrapPort;
    private final IPasswordHasher passwordHasher;
    private final ISmsSenderPort smsSenderPort;
    private final IAuthThrottlePort authThrottlePort;
    private final IUserStatusRepository userStatusRepository;
    private final ISocialIdPort socialIdPort;

    /**
     * 发送短信验证码。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     * @param ip 请求 IP
     */
    public void sendSms(String phone, AuthSmsBizTypeVO bizType, String ip) {
        String normalizedPhone = requireText(phone, "phone 不能为空");
        String normalizedIp = requireText(ip, "ip 不能为空");
        if (bizType == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "bizType 不能为空");
        }

        authThrottlePort.checkSmsSendLimit(normalizedPhone, normalizedIp);

        String code = generateSmsCode();
        String codeHash = safeHash(code);
        Long expireAt = now() + SMS_EXPIRE_MS;
        boolean sent = smsSenderPort.send(normalizedPhone, code, bizType);
        if (!sent) {
            authSmsCodeRepository.saveFailedAttempt(normalizedPhone, bizType, codeHash, expireAt, normalizedIp);
            throw new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo());
        }

        authSmsCodeRepository.invalidateLatest(normalizedPhone, bizType);
        authSmsCodeRepository.saveLatest(normalizedPhone, bizType, codeHash, expireAt, normalizedIp, SMS_SEND_STATUS_SENT);
        authThrottlePort.onSmsSend(normalizedPhone, normalizedIp);
    }

    /**
     * 手机号注册。
     *
     * @param phone 手机号
     * @param smsCode 验证码
     * @param password 密码
     * @param nickname 昵称
     * @param avatarUrl 头像
     * @return 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long register(String phone, String smsCode, String password, String nickname, String avatarUrl) {
        String normalizedPhone = requireText(phone, "phone 不能为空");
        String normalizedSmsCode = requireText(smsCode, "smsCode 不能为空");
        String normalizedPassword = requireText(password, "password 不能为空");

        authSmsCodeRepository.requireLatestValid(normalizedPhone, AuthSmsBizTypeVO.REGISTER, normalizedSmsCode);
        if (authAccountRepository.existsByPhone(normalizedPhone)) {
            throw new AppException(ResponseCode.CONFLICT.getCode(), ResponseCode.CONFLICT.getInfo());
        }

        Long userId = requireId(socialIdPort.nextId(), "userId 生成失败");
        Long now = now();
        authAccountRepository.create(AuthAccountEntity.builder()
                .userId(userId)
                .phone(normalizedPhone)
                .passwordHash(safeHash(normalizedPassword))
                .passwordUpdatedAt(now)
                .createTime(now)
                .updateTime(now)
                .build());
        authUserBaseRepository.create(userId, "u" + userId, normalizeNickname(nickname, userId), avatarUrl);
        authRoleRepository.assignRole(userId, ROLE_USER);
        grantBootstrapAdminIfNeeded(normalizedPhone, userId);
        userStatusRepository.upsertStatus(userId, STATUS_ACTIVE, null);
        authSmsCodeRepository.markUsed(normalizedPhone, AuthSmsBizTypeVO.REGISTER, normalizedSmsCode);
        return userId;
    }

    /**
     * 密码登录。
     *
     * @param phone 手机号
     * @param rawPassword 原始密码
     * @return 登录结果
     */
    public AuthLoginResultVO passwordLogin(String phone, String rawPassword) {
        String normalizedPhone = requireText(phone, "phone 不能为空");
        String normalizedPassword = requireText(rawPassword, "password 不能为空");

        authThrottlePort.checkLoginLock(LOGIN_TYPE_PASSWORD, normalizedPhone);
        AuthAccountEntity account;
        try {
            account = requireAccountByPhone(normalizedPhone);
            ensureUserActive(account.getUserId());
            if (!passwordHasher.matches(normalizedPassword, account.getPasswordHash())) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "password 不正确");
            }
        } catch (AppException e) {
            authThrottlePort.onLoginFailure(LOGIN_TYPE_PASSWORD, normalizedPhone);
            throw e;
        }

        authThrottlePort.onLoginSuccess(LOGIN_TYPE_PASSWORD, normalizedPhone);
        grantBootstrapAdminIfNeeded(account.getPhone(), account.getUserId());
        authAccountRepository.touchLastLogin(account.getUserId(), now());
        return AuthLoginResultVO.builder()
                .userId(account.getUserId())
                .phone(account.getPhone())
                .build();
    }

    /**
     * 验证码登录。
     *
     * @param phone 手机号
     * @param smsCode 验证码
     * @return 登录结果
     */
    public AuthLoginResultVO smsLogin(String phone, String smsCode) {
        String normalizedPhone = requireText(phone, "phone 不能为空");
        String normalizedSmsCode = requireText(smsCode, "smsCode 不能为空");

        authThrottlePort.checkLoginLock(LOGIN_TYPE_SMS, normalizedPhone);
        AuthAccountEntity account;
        try {
            account = requireAccountByPhone(normalizedPhone);
            authSmsCodeRepository.requireLatestValid(normalizedPhone, AuthSmsBizTypeVO.LOGIN, normalizedSmsCode);
            ensureUserActive(account.getUserId());
        } catch (AppException e) {
            authThrottlePort.onLoginFailure(LOGIN_TYPE_SMS, normalizedPhone);
            throw e;
        }

        authSmsCodeRepository.markUsed(normalizedPhone, AuthSmsBizTypeVO.LOGIN, normalizedSmsCode);
        authThrottlePort.onLoginSuccess(LOGIN_TYPE_SMS, normalizedPhone);
        grantBootstrapAdminIfNeeded(account.getPhone(), account.getUserId());
        authAccountRepository.touchLastLogin(account.getUserId(), now());
        return AuthLoginResultVO.builder()
                .userId(account.getUserId())
                .phone(account.getPhone())
                .build();
    }

    /**
     * 修改密码。
     *
     * @param userId 用户 ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 当前用户 ID，供上层失效旧会话
     */
    @Transactional(rollbackFor = Exception.class)
    public Long changePassword(Long userId, String oldPassword, String newPassword) {
        Long normalizedUserId = requireId(userId, "userId 不能为空");
        String normalizedOldPassword = requireText(oldPassword, "oldPassword 不能为空");
        String normalizedNewPassword = requireText(newPassword, "newPassword 不能为空");

        ensureUserActive(normalizedUserId);
        AuthAccountEntity account = requireAccountByUserId(normalizedUserId);
        if (!passwordHasher.matches(normalizedOldPassword, account.getPasswordHash())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "oldPassword 不正确");
        }

        authAccountRepository.updatePassword(normalizedUserId, safeHash(normalizedNewPassword), now());
        return normalizedUserId;
    }

    /**
     * 授予管理员角色。
     *
     * @param userId 目标用户 ID
     * @return 目标用户 ID
     */
    public Long grantAdmin(Long userId) {
        Long normalizedUserId = requireId(userId, "userId 不能为空");
        ensureUserActive(normalizedUserId);
        requireAccountByUserId(normalizedUserId);
        authRoleRepository.assignRole(normalizedUserId, ROLE_ADMIN);
        return normalizedUserId;
    }

    /**
     * 撤销管理员角色。
     *
     * @param userId 目标用户 ID
     * @return 目标用户 ID
     */
    public Long revokeAdmin(Long userId) {
        Long normalizedUserId = requireId(userId, "userId 不能为空");
        ensureUserActive(normalizedUserId);
        requireAccountByUserId(normalizedUserId);
        authRoleRepository.removeRole(normalizedUserId, ROLE_ADMIN);
        return normalizedUserId;
    }

    /**
     * 查询管理员用户 ID 列表。
     *
     * @return 管理员用户 ID 列表
     */
    public java.util.List<Long> listAdminUserIds() {
        return authRoleRepository.listUserIdsByRoleCode(ROLE_ADMIN);
    }

    /**
     * 查询管理员详情列表。
     *
     * @return 管理员详情列表
     */
    public List<AuthAdminVO> listAdmins() {
        List<Long> userIds = listAdminUserIds();
        if (userIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AuthAccountEntity> accounts = new LinkedHashMap<>();
        for (AuthAccountEntity account : authAccountRepository.listByUserIds(userIds)) {
            if (account != null && account.getUserId() != null) {
                accounts.put(account.getUserId(), account);
            }
        }

        Map<Long, AuthMeVO> profiles = new LinkedHashMap<>();
        for (AuthMeVO profile : authUserBaseRepository.listByUserIds(userIds)) {
            if (profile != null && profile.getUserId() != null) {
                profiles.put(profile.getUserId(), profile);
            }
        }

        List<AuthAdminVO> admins = new ArrayList<>();
        for (Long userId : userIds) {
            AuthAccountEntity account = accounts.get(userId);
            AuthMeVO profile = profiles.get(userId);
            admins.add(AuthAdminVO.builder()
                    .userId(userId)
                    .phone(account == null ? null : account.getPhone())
                    .status(userStatusRepository.getStatus(userId))
                    .nickname(profile == null ? null : profile.getNickname())
                    .avatarUrl(profile == null ? null : profile.getAvatarUrl())
                    .build());
        }
        return admins;
    }

    /**
     * 查询当前登录用户信息。
     *
     * @param userId 用户 ID
     * @return 当前用户信息
     */
    public AuthMeVO me(Long userId) {
        Long normalizedUserId = requireId(userId, "userId 不能为空");
        AuthAccountEntity account = requireAccountByUserId(normalizedUserId);
        AuthMeVO profile = authUserBaseRepository.getMe(normalizedUserId);
        if (profile == null) {
            profile = AuthMeVO.builder().userId(normalizedUserId).build();
        }
        return AuthMeVO.builder()
                .userId(normalizedUserId)
                .phone(account.getPhone())
                .status(userStatusRepository.getStatus(normalizedUserId))
                .nickname(profile.getNickname())
                .avatarUrl(profile.getAvatarUrl())
                .build();
    }

    private AuthAccountEntity requireAccountByPhone(String phone) {
        AuthAccountEntity account = authAccountRepository.requireByPhone(phone);
        if (account == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        return account;
    }

    private AuthAccountEntity requireAccountByUserId(Long userId) {
        AuthAccountEntity account = authAccountRepository.requireByUserId(userId);
        if (account == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        return account;
    }

    private void ensureUserActive(Long userId) {
        String status = userStatusRepository.getStatus(userId);
        if (STATUS_DEACTIVATED.equals(status)) {
            throw new AppException(ResponseCode.USER_DEACTIVATED.getCode(), ResponseCode.USER_DEACTIVATED.getInfo());
        }
    }

    private Long requireId(Long value, String message) {
        if (value == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), message);
        }
        return value;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
        }
        return value.trim();
    }

    private String normalizeNickname(String nickname, Long userId) {
        if (nickname == null || nickname.isBlank()) {
            return "u" + userId;
        }
        return nickname.trim();
    }

    private String safeHash(String raw) {
        String hashed = passwordHasher.hash(raw);
        if (hashed != null && !hashed.isBlank()) {
            return hashed;
        }
        return Integer.toHexString(raw.hashCode());
    }

    private void grantBootstrapAdminIfNeeded(String phone, Long userId) {
        if (authAdminBootstrapPort.shouldGrantAdmin(phone)) {
            authRoleRepository.assignRole(userId, ROLE_ADMIN);
        }
    }

    private Long now() {
        Long now = socialIdPort.now();
        return now != null ? now : System.currentTimeMillis();
    }

    private String generateSmsCode() {
        // 当前服务还没有独立的验证码生成端口，先把生成逻辑集中在这里，后续替换时只改一处。
        return "123456";
    }
}
