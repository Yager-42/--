# Auth Closed Loop Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `nexus` 中删除旧开发态登录，落地手机号注册、密码登录、验证码登录、改密码、登出、`/auth/me`、短信防刷这一整套正式认证闭环。

**Architecture:** 新增 `domain.auth` 作为认证领域，`auth_account` 负责账号和密码真相源，`auth_sms_code` 负责验证码生命周期，`user_base` 继续承担公开资料和 `username`，`user_status` 继续承担账号状态真相源。Trigger 层只收口 HTTP 和 Sa-Token，会话身份只从 token 解析，不再接受 header bypass。

**Tech Stack:** Spring Boot 3.2, MyBatis, MySQL, Redis, Sa-Token, JUnit 5, Mockito, Maven

---

**Execution Notes:**

- 所有带 `-Dtest=...` 的 Maven 命令都必须附带 `-DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`，否则上游无测试模块会先失败。
- `nexus-domain` 不能直接依赖 `nexus-infrastructure` 或 `StpUtil`；领域层只依赖仓储/端口，登录态建立和失效由 Trigger 层负责。

## Chunk 1: Schema And API Contract

### Task 1: 建认证表迁移与认证 API 契约

**Files:**
- Create: `docs/migrations/20260318_01_add_auth_tables.sql`
- Modify: `nexus-api/src/main/java/cn/nexus/api/auth/IAuthApi.java`
- Create: `nexus-api/src/test/java/cn/nexus/api/auth/AuthApiContractTest.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginRequestDTO.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginResponseDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthSmsSendRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthSmsSendResponseDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthRegisterRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthRegisterResponseDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthPasswordLoginRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthSmsLoginRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthTokenResponseDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthChangePasswordRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthMeResponseDTO.java`
- Test: `nexus-api/src/test/java/cn/nexus/api/auth/AuthApiContractTest.java`

- [ ] **Step 1: 先写 API 契约测试，明确旧登录方法已移除、新接口存在**

```java
@Test
void authApi_shouldExposeSmsSendRegisterPasswordLoginSmsLoginChangePasswordLogoutAndMe() {
    // 断言 IAuthApi 只保留新认证方法，不再有 login(AuthLoginRequestDTO)
}
```

- [ ] **Step 2: 运行测试，确认它因旧契约仍存在而失败**

Run: `mvn -pl nexus-api -am -Dtest=AuthApiContractTest#authApi_shouldExposeSmsSendRegisterPasswordLoginSmsLoginChangePasswordLogoutAndMe -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，失败点是旧 `login(AuthLoginRequestDTO)` 仍存在，或新 DTO / 新方法缺失。

- [ ] **Step 3: 新建 SQL migration**

```sql
CREATE TABLE IF NOT EXISTS auth_account (
  account_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  phone VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_auth_account_user_id (user_id),
  UNIQUE KEY uk_auth_account_phone (phone)
);

CREATE TABLE IF NOT EXISTS auth_sms_code (
  id BIGINT NOT NULL,
  biz_type VARCHAR(32) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  expire_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  verify_fail_count INT NOT NULL DEFAULT 0,
  send_status VARCHAR(32) NOT NULL DEFAULT 'SENT',
  request_ip VARCHAR(64) NOT NULL DEFAULT '',
  latest_flag TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_auth_sms_code_lookup (phone, biz_type, latest_flag, create_time)
);
```

- [ ] **Step 4: 重写 `IAuthApi.java` 为正式认证契约**

```java
Response<AuthSmsSendResponseDTO> sendSms(AuthSmsSendRequestDTO requestDTO);
Response<AuthRegisterResponseDTO> register(AuthRegisterRequestDTO requestDTO);
Response<AuthTokenResponseDTO> passwordLogin(AuthPasswordLoginRequestDTO requestDTO);
Response<AuthTokenResponseDTO> smsLogin(AuthSmsLoginRequestDTO requestDTO);
Response<Void> logout();
Response<Void> changePassword(AuthChangePasswordRequestDTO requestDTO);
Response<AuthMeResponseDTO> me();
```

- [ ] **Step 5: 删除旧 DTO，创建新 DTO**

```java
class AuthSmsSendRequestDTO { String phone; String bizType; }
class AuthRegisterRequestDTO { String phone; String smsCode; String password; String nickname; String avatarUrl; }
class AuthTokenResponseDTO { Long userId; String tokenName; String tokenPrefix; String token; }
```

- [ ] **Step 6: 重新运行 API 契约测试，确认通过**

Run: `mvn -pl nexus-api -am -Dtest=AuthApiContractTest#authApi_shouldExposeSmsSendRegisterPasswordLoginSmsLoginChangePasswordLogoutAndMe -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add docs/migrations/20260318_01_add_auth_tables.sql nexus-api/src/main/java/cn/nexus/api/auth
git commit -m "feat: add auth api contract and schema"
```

### Task 2: 建立 auth DAO 与 mapper

**Files:**
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/auth/IAuthAccountDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/auth/IAuthSmsCodeDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/auth/po/AuthAccountPO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/auth/po/AuthSmsCodePO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/auth/AuthAccountMapper.xml`
- Create: `nexus-infrastructure/src/main/resources/mapper/auth/AuthSmsCodeMapper.xml`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/dao/auth/AuthMapperContractTest.java`

- [ ] **Step 1: 先写 DAO 契约测试**

```java
@Test
void authAccountMapper_shouldSupportFindByPhoneInsertUpdatePasswordAndTouchLastLogin() {}

@Test
void authSmsCodeMapper_shouldSupportInsertInvalidatePreviousFindLatestAndMarkUsed() {}
```

- [ ] **Step 2: 运行测试，确认因为 auth DAO 不存在而失败**

Run: `mvn -pl nexus-infrastructure -am -Dtest=AuthMapperContractTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，提示找不到新 DAO / mapper。

- [ ] **Step 3: 创建 `IAuthAccountDao`**

```java
AuthAccountPO selectByPhone(@Param("phone") String phone);
AuthAccountPO selectByUserId(@Param("userId") Long userId);
int insert(AuthAccountPO po);
int updatePassword(@Param("userId") Long userId, @Param("passwordHash") String passwordHash, @Param("passwordUpdatedAt") Date passwordUpdatedAt);
int touchLastLogin(@Param("userId") Long userId, @Param("lastLoginAt") Date lastLoginAt);
```

- [ ] **Step 4: 创建 `IAuthSmsCodeDao`**

```java
int invalidateLatest(@Param("phone") String phone, @Param("bizType") String bizType);
int insert(AuthSmsCodePO po);
AuthSmsCodePO selectLatestActive(@Param("phone") String phone, @Param("bizType") String bizType);
int incrementVerifyFail(@Param("id") Long id);
int markUsed(@Param("id") Long id, @Param("usedAt") Date usedAt);
```

- [ ] **Step 5: 写 MyBatis XML**

```xml
<select id="selectLatestActive">
  SELECT * FROM auth_sms_code
  WHERE phone = #{phone}
    AND biz_type = #{bizType}
    AND latest_flag = 1
    AND used_at IS NULL
  ORDER BY id DESC
  LIMIT 1
</select>
```

- [ ] **Step 6: 运行 DAO 契约测试，确认通过**

Run: `mvn -pl nexus-infrastructure -am -Dtest=AuthMapperContractTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/auth nexus-infrastructure/src/main/resources/mapper/auth nexus-infrastructure/src/test/java/cn/nexus/infrastructure/dao/auth/AuthMapperContractTest.java
git commit -m "feat: add auth dao and mapper"
```

## Chunk 2: Domain And Infrastructure Adapters

### Task 3: 建领域模型、仓储接口和基础端口

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/repository/IAuthAccountRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/repository/IAuthSmsCodeRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/repository/IAuthUserBaseRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/port/IPasswordHasher.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/port/ISmsSenderPort.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/port/IAuthThrottlePort.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/model/entity/AuthAccountEntity.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/model/entity/AuthSmsCodeEntity.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/model/valobj/AuthSmsBizTypeVO.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/model/valobj/AuthLoginResultVO.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/model/valobj/AuthMeVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/repository/AuthAccountRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/repository/AuthSmsCodeRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/repository/AuthUserBaseRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/port/BcryptPasswordHasher.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/port/LoggingSmsSenderPort.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth/port/RedisAuthThrottlePort.java`
- Test: `nexus-domain/src/test/java/cn/nexus/domain/auth/AuthModelContractTest.java`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/auth/RedisAuthThrottlePortTest.java`

- [ ] **Step 1: 先写领域与端口测试**

```java
@Test
void authSmsBizType_shouldOnlyAllowRegisterAndLogin() {}

@Test
void redisAuthThrottlePort_shouldApplySmsAndLoginLockWindows() {}
```

- [ ] **Step 2: 运行测试，确认因为端口和模型缺失而失败**

Run: `mvn -pl nexus-domain,nexus-infrastructure -am -Dtest=AuthModelContractTest,RedisAuthThrottlePortTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL

- [ ] **Step 3: 创建值对象与实体**

```java
enum AuthSmsBizTypeVO { REGISTER, LOGIN }

class AuthAccountEntity {
  Long accountId;
  Long userId;
  String phone;
  String passwordHash;
}
```

- [ ] **Step 4: 创建端口接口**

```java
interface IPasswordHasher {
  String hash(String rawPassword);
  boolean matches(String rawPassword, String passwordHash);
}

interface IAuthUserBaseRepository {
  void create(Long userId, String username, String nickname, String avatarUrl);
  AuthMeVO getMe(Long userId);
}

interface IAuthThrottlePort {
  void checkSmsSendLimit(String phone, String ip);
  void onSmsSend(String phone, String ip);
  void checkLoginLock(String type, String phone);
  void onLoginFail(String type, String phone);
  void clearLoginFail(String type, String phone);
}
```

- [ ] **Step 5: 创建基础设施适配器**

```java
class BcryptPasswordHasher implements IPasswordHasher { ... }
class LoggingSmsSenderPort implements ISmsSenderPort { ... } // 先打日志，后接真实短信商
class RedisAuthThrottlePort implements IAuthThrottlePort { ... } // 用 Redis key 实现窗口限制和锁定
```

- [ ] **Step 6: 运行模型与端口测试，确认通过**

Run: `mvn -pl nexus-domain,nexus-infrastructure -am -Dtest=AuthModelContractTest,RedisAuthThrottlePortTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/auth nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/auth nexus-domain/src/test/java/cn/nexus/domain/auth/AuthModelContractTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/auth/RedisAuthThrottlePortTest.java
git commit -m "feat: add auth domain model and infrastructure ports"
```

### Task 4: 实现 `AuthService`

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/service/AuthService.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/auth/service/IAuthService.java`
- Test: `nexus-domain/src/test/java/cn/nexus/domain/auth/service/AuthServiceTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/user/service/UserService.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/user/adapter/repository/IUserStatusRepository.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/auth/adapter/repository/IAuthUserBaseRepository.java`

- [ ] **Step 1: 先写 `AuthServiceTest`，覆盖发验证码、注册、密码登录、验证码登录、改密**

```java
@Test
void sendSms_shouldApplyRateLimitInvalidateOldCodeAndStoreLatestCode() {}

@Test
void sendSms_whenProviderFails_shouldKeepOldLatestCodeAndRecordFailedSend() {}

@Test
void register_shouldCreateAuthAccountUserBaseAndActiveStatus() {}

@Test
void passwordLogin_shouldRejectDeactivatedUser() {}

@Test
void smsLogin_shouldUseLatestUnusedCodeForLoginBizType() {}

@Test
void changePassword_shouldReturnUserIdForSessionInvalidation() {}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -pl nexus-domain -am -Dtest=AuthServiceTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，提示 `AuthService` 不存在或行为未实现。

- [ ] **Step 3: 写 `sendSms` 逻辑**

```java
validatePhoneAndBizType();
throttlePort.checkSmsSendLimit(phone, ip);
boolean sent = smsSenderPort.send(phone, code, bizType);
if (!sent) {
  smsCodeRepository.saveFailedAttempt(phone, bizType, passwordHasher.hash(code), expireAt, ip);
  throw new AppException("SMS_SEND_FAILED");
}
smsCodeRepository.invalidateLatest(phone, bizType);
smsCodeRepository.saveLatest(phone, bizType, passwordHasher.hash(code), expireAt, ip, "SENT");
throttlePort.onSmsSend(phone, ip);
```

- [ ] **Step 4: 写注册逻辑**

```java
validatePhoneAndPassword();
smsCodeRepository.requireLatestValid(phone, REGISTER, code);
ensurePhoneNotRegistered(phone);
Long userId = socialIdPort.nextId();
authAccountRepository.create(...);
userBaseRepository.create(userId, "u" + userId, nicknameOrDefault, avatarOrDefault);
userStatusRepository.upsertStatus(userId, "ACTIVE", null);
```

- [ ] **Step 5: 写密码登录和验证码登录逻辑**

```java
throttlePort.checkLoginLock("password", phone);
AuthAccountEntity account = authAccountRepository.requireByPhone(phone);
ensureUserActive(account.getUserId());
passwordHasher.matches(raw, account.getPasswordHash());
authAccountRepository.touchLastLogin(account.getUserId(), now);
return new AuthLoginResultVO(account.getUserId(), account.getPhone());
```

- [ ] **Step 6: 写改密码与 `/auth/me` 逻辑**

```java
Long userId = currentUserId;
ensureUserActive(userId);
verifyOldPassword();
authAccountRepository.updatePassword(userId, passwordHasher.hash(newPassword), now);
return userId; // 由 Trigger 层拿这个 userId 去踢会话

AuthMeVO me(Long userId) {
  AuthAccountEntity account = authAccountRepository.requireByUserId(userId);
  AuthMeVO profile = userBaseRepository.getMe(userId);
  return merge(account, userStatusRepository.getStatus(userId), profile);
}
```

- [ ] **Step 7: 运行 `AuthServiceTest`，确认通过**

Run: `mvn -pl nexus-domain -am -Dtest=AuthServiceTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/auth nexus-domain/src/test/java/cn/nexus/domain/auth/service/AuthServiceTest.java
git commit -m "feat: add auth service"
```

## Chunk 3: Trigger Layer And Breaking Cleanup

### Task 5: 重写 `AuthController` 并打通匿名入口

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/config/WebMvcConfig.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/support/UserContextInterceptorTest.java`

- [ ] **Step 1: 先写 Trigger 层测试**

```java
@Test
void unauthenticatedEndpoints_shouldPassWithoutToken() {}

@Test
void protectedEndpoint_shouldRejectForgedUserIdHeaderWithoutToken() {}

@Test
void changePassword_shouldRequireToken() {}

@Test
void logout_shouldRequireTokenAndInvalidateCurrentSession() {}

@Test
void changePassword_shouldInvalidateOldTokenAtHttpLayer() {}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -pl nexus-trigger -am -Dtest=AuthControllerTest,UserContextInterceptorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，原因是旧 `/api/v1/auth/login` 仍被白名单放行，或 `UserContextInterceptor` 仍接受 header bypass。

- [ ] **Step 3: 重写 `AuthController.java`**

```java
@PostMapping("/sms/send")
public Response<AuthSmsSendResponseDTO> sendSms(@RequestBody AuthSmsSendRequestDTO requestDTO) { ... }

@PostMapping("/login/password")
public Response<AuthTokenResponseDTO> passwordLogin(@RequestBody AuthPasswordLoginRequestDTO requestDTO) { ... }

@PostMapping("/login/sms")
public Response<AuthTokenResponseDTO> smsLogin(@RequestBody AuthSmsLoginRequestDTO requestDTO) { ... }

@PostMapping("/register")
public Response<AuthRegisterResponseDTO> register(@RequestBody AuthRegisterRequestDTO requestDTO) { ... }

@PostMapping("/password/change")
public Response<Void> changePassword(@RequestBody AuthChangePasswordRequestDTO requestDTO) { ... }

@PostMapping("/logout")
public Response<Void> logout() { ... }

@GetMapping("/me")
public Response<AuthMeResponseDTO> me() { ... }
```

要求：

- `passwordLogin` / `smsLogin` 在 controller 中调用 `authService` 拿 `userId` 后执行 `StpUtil.login(userId)`
- `changePassword` 在 controller 中调用 `authService.changePassword(...)` 返回 `userId` 后执行按用户会话失效
- `logout` 只处理当前会话失效，不把会话逻辑塞回领域层

- [ ] **Step 4: 修改 `WebMvcConfig.java` 白名单**

```java
.excludePathPatterns(
  "/api/v1/health",
  "/api/v1/health/**",
  "/api/v1/auth/sms/send",
  "/api/v1/auth/register",
  "/api/v1/auth/login/password",
  "/api/v1/auth/login/sms"
);
```

- [ ] **Step 5: 修改 `UserContextInterceptor.java`**

```java
Long tokenUserId = resolveUserIdFromToken();
if (tokenUserId == null) {
  writeIllegalParameter(response);
  return false;
}
UserContext.setUserId(tokenUserId);
return true;
```

- [ ] **Step 6: 运行 Trigger 层测试，确认通过**

Run: `mvn -pl nexus-trigger -am -Dtest=AuthControllerTest,UserContextInterceptorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java nexus-trigger/src/main/java/cn/nexus/trigger/http/config/WebMvcConfig.java nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java nexus-trigger/src/test/java/cn/nexus/trigger/http/support/UserContextInterceptorTest.java
git commit -m "feat: wire auth http endpoints"
```

### Task 6: 清理旧开发态登录并补配置

**Files:**
- Delete: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginRequestDTO.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginResponseDTO.java`
- Modify: `nexus-app/src/main/resources/application-dev.yml`
- Modify: `docs/xhs-playbook-adapter.md`
- Modify: `docs/xiaohashu_project_implementation_playbook.md`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java`

- [ ] **Step 1: 先写“旧登录已删除”的回归测试**

```java
@Test
void oldDevLoginRoute_shouldNotExistAnywhere() {}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -pl nexus-trigger -am -Dtest=AuthControllerTest#oldDevLoginRoute_shouldNotExistAnywhere -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，提示旧 DTO 或旧路由仍在。

- [ ] **Step 3: 删除旧 DTO / 旧说明，补新配置**

```yaml
auth:
  sms:
    code-expire-seconds: 300
    phone-send-limit-1h: 5
    phone-send-limit-1d: 10
    ip-send-limit-1m: 5
    ip-send-limit-1d: 50
  login:
    fail-threshold: 5
    lock-seconds: 900
```

- [ ] **Step 4: 更新文档，删掉“开发态登录”和 header 身份兼容描述**

```markdown
- 移除 `POST /api/v1/auth/login`
- 替换为 `/api/v1/auth/login/password` 和 `/api/v1/auth/login/sms`
- 删除 `userId` / `X-User-Id` header 兼容说明，只保留 `Authorization: Bearer <token>`
- 在 `xhs-playbook-adapter.md` 与 `xiaohashu_project_implementation_playbook.md` 中同步删掉 header 注入身份的旧描述
```

- [ ] **Step 5: 运行旧登录删除测试，确认通过**

Run: `mvn -pl nexus-trigger -am -Dtest=AuthControllerTest#oldDevLoginRoute_shouldNotExistAnywhere -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-app/src/main/resources/application-dev.yml docs/xhs-playbook-adapter.md nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java
git commit -m "refactor: remove dev login path"
```

## Chunk 4: End-To-End Verification

### Task 7: 全量认证回归

**Files:**
- Test: `nexus-domain/src/test/java/cn/nexus/domain/auth/service/AuthServiceTest.java`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/dao/auth/AuthMapperContractTest.java`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/auth/RedisAuthThrottlePortTest.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/support/UserContextInterceptorTest.java`
- Reference: `docs/superpowers/specs/2026-03-18-auth-closed-loop-design.md`

- [ ] **Step 1: 跑领域与基础设施测试**

Run: `mvn -pl nexus-domain,nexus-infrastructure -am -Dtest=AuthServiceTest,AuthMapperContractTest,RedisAuthThrottlePortTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 2: 跑 Trigger 层测试**

Run: `mvn -pl nexus-trigger -am -Dtest=AuthControllerTest,UserContextInterceptorTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS

- [ ] **Step 3: 跑认证相关全链路测试**

Run: `mvn -pl nexus-trigger -am test`

Expected: PASS；至少不应出现：
- 旧 `/api/v1/auth/login` 路由残留
- 匿名接口被拦截
- header 伪造身份仍可通过
- 改密后旧 token 仍可访问

- [ ] **Step 4: 手工冒烟**

```text
1. 未登录调用 /api/v1/auth/register -> 可达
2. 未登录调用 /api/v1/auth/login/password -> 可达
3. 未登录调用受保护接口 + 伪造 X-User-Id -> 被拒绝
4. 登录成功后调用 /api/v1/auth/me -> 返回 userId/phone/status/nickname/avatarUrl
5. 改密码后旧 token 调用 /api/v1/auth/me -> 失败
```

- [ ] **Step 5: 汇总验证记录**

```markdown
- 记录执行命令
- 记录通过结果
- 记录剩余风险（短信供应商当前为日志实现）
```

- [ ] **Step 6: Final commit**

```bash
git add .
git commit -m "feat: complete auth closed loop"
```
