# Nexus 认证闭环设计

**日期**: 2026-03-18

**目标**

把当前 `nexus` 里“开发态假登录”替换为正式认证闭环，先完成以下范围：

- 账号密码登录
- 手机验证码登录
- 发送短信验证码
- 自注册
- 改密码
- 登出
- 短信防刷

本轮**不做** RBAC。

## 1. 已确认的关键决策

### 1.1 登录主键

- 手机号是唯一登录主键。
- `user_base` 不再承担认证职责，但继续承担公开资料和唯一公开标识 `username` 的职责。

### 1.2 账号与资料分离

- 认证真相源新增 `auth_account`。
- 展示真相源继续沿用 `user_base`。
- 认证负责“你是谁”和“凭证是什么”。
- `user_base` 负责公开资料以及唯一公开标识 `username`。

### 1.3 删除旧登录

- 删除旧接口：`POST /api/v1/auth/login`
- 删除旧实现：开发态 `AuthController.login(...)`
- 删除旧 DTO / API 契约 / 测试 / 白名单配置
- 删除 `UserContextInterceptor` 中基于 `userId` / `X-User-Id` 的 header 身份注入兜底
- 不保留开关，不保留兼容层，不保留 dev bypass

这是**明确的破坏性变更**。所有依赖旧登录的调用方都必须切到新接口。

## 2. 目标边界

### 2.1 本轮要做

- 手机号注册
- 手机号 + 密码登录
- 手机号 + 验证码登录
- 已登录用户改密码
- 当前 token 登出
- 查询当前登录用户
- 短信验证码防刷
- 密码 / 验证码错误次数限流
- 新匿名认证接口白名单
- 受保护接口只认 token，不再认 header bypass

### 2.2 本轮不做

- RBAC
- 刷新 token
- 邮箱登录
- 第三方登录
- 手机号换绑
- 找回密码独立流程
- 多端设备管理

## 3. 数据模型

### 3.1 `user_base`

继续保留现有职责：

- `user_id`
- `username`
- `nickname`
- `avatar_url`

说明：

- 注册成功时同步创建 `user_base`
- `username` 继续作为公开唯一标识，供 `@username`、资料查询和一致性校验复用
- 本轮不开放用户自定义 `username`
- 注册时系统自动生成：`username = "u" + userId`
- `username` 生成或写入失败时整笔注册事务回滚

### 3.2 `auth_account`

建议字段：

- `account_id` BIGINT 主键
- `user_id` BIGINT 唯一
- `phone` VARCHAR(32) 唯一
- `password_hash` VARCHAR(255)
- `password_updated_at` DATETIME
- `last_login_at` DATETIME
- `create_time` DATETIME
- `update_time` DATETIME

约束：

- 一个手机号只对应一个账号
- 一个 `user_id` 只对应一个认证账号

状态真相源约定：

- 本轮账号可登录状态只认 `user_status`
- `auth_account` 不再单独维护第二套登录状态
- 注册默认写入 `user_status=ACTIVE`
- 登录、验证码登录、改密码、`/auth/me` 读取的状态都以 `user_status` 为准

### 3.3 `auth_sms_code`

建议字段：

- `id` BIGINT 主键
- `biz_type` VARCHAR(32)
- `phone` VARCHAR(32)
- `code_hash` VARCHAR(255)
- `expire_at` DATETIME
- `used_at` DATETIME NULL
- `verify_fail_count` INT
- `send_status` VARCHAR(32)
- `request_ip` VARCHAR(64)
- `latest_flag` TINYINT
- `create_time` DATETIME

说明：

- 只存验证码 hash，不存明文
- 新验证码发出后，旧验证码作废
- 一个验证码只能成功使用一次
- 校验时必须同时匹配 `phone + biz_type + latest_flag=1 + used_at is null`

### 3.4 Redis 防刷键

建议先用 Redis，不新增 MySQL 表：

- `auth:sms:send:phone:{phone}:1m`
- `auth:sms:send:phone:{phone}:1h`
- `auth:sms:send:phone:{phone}:1d`
- `auth:sms:send:ip:{ip}:1m`
- `auth:sms:send:ip:{ip}:1d`
- `auth:login:fail:password:{phone}`
- `auth:login:fail:sms:{phone}`
- `auth:login:lock:{type}:{phone}`

## 4. API 设计

### 4.1 发送验证码

- `POST /api/v1/auth/sms/send`

请求：

- `phone`
- `bizType`

`bizType` 只允许：

- `REGISTER`
- `LOGIN`

返回：

- 成功标记
- 验证码过期秒数

### 4.2 注册

- `POST /api/v1/auth/register`

请求：

- `phone`
- `smsCode`
- `password`
- `nickname` 可选
- `avatarUrl` 可选

规则：

- 手机号已注册则失败
- 验证码必须正确、未过期，且必须是该手机号 `REGISTER` 业务下最新一条未使用验证码
- 成功后创建 `auth_account`
- 成功后创建 `user_base`
- 成功后创建默认 `user_status=ACTIVE`

### 4.3 密码登录

- `POST /api/v1/auth/login/password`

请求：

- `phone`
- `password`

返回：

- `userId`
- `tokenName`
- `tokenPrefix`
- `token`

### 4.4 验证码登录

- `POST /api/v1/auth/login/sms`

请求：

- `phone`
- `smsCode`

规则：

- 只允许已注册手机号登录
- 不存在则返回 `PHONE_NOT_REGISTERED`
- 不自动注册

### 4.5 改密码

- `POST /api/v1/auth/password/change`

请求：

- `oldPassword`
- `newPassword`

规则：

- 必须已登录
- 先校验旧密码
- 成功后更新 `password_hash`
- 成功后使该用户所有旧 token 失效，要求重新登录

### 4.6 登出

- `POST /api/v1/auth/logout`

规则：

- 让当前 token 失效

### 4.7 当前登录用户

- `GET /api/v1/auth/me`

返回：

- `userId`
- `phone`
- `status`（来自 `user_status`）
- `nickname`
- `avatarUrl`

### 4.8 匿名入口与鉴权入口

必须显式放行的匿名入口：

- `POST /api/v1/auth/sms/send`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login/password`
- `POST /api/v1/auth/login/sms`

其余受保护接口规则：

- 只认 token
- 不再接受 `userId` / `X-User-Id` header 直接注入身份
- `UserContextInterceptor` 只从 token 解析 `userId`

## 5. 核心流程

### 5.1 发送验证码

1. 校验 `phone` 和 `bizType`
2. 检查手机号/IP 限流
3. 生成 6 位验证码
4. 把同手机号同业务旧验证码标记为非最新
5. 存 hash 到 `auth_sms_code`
6. 调用短信发送端口
7. 返回成功

### 5.2 注册

1. 校验请求参数
2. 确认手机号未注册
3. 校验 `REGISTER` 验证码，且必须是该手机号最新一条未使用验证码
4. 生成 `user_id`
5. 写 `auth_account`
6. 写 `user_base`，其中 `username = "u" + userId`
7. 写默认 `user_status=ACTIVE`
8. 返回注册成功

### 5.3 密码登录

1. 校验手机号是否存在
2. 检查是否已被锁定
3. 校验 `user_status=ACTIVE`
4. 校验密码 hash
5. 成功则清空失败计数
6. `StpUtil.login(userId)`
7. 更新最近登录时间
8. 返回 token

### 5.4 验证码登录

1. 校验手机号是否存在
2. 检查验证码登录是否被锁定
3. 校验 `LOGIN` 验证码，且必须是该手机号最新一条未使用验证码
4. 校验 `user_status=ACTIVE`
5. 成功则作废验证码
6. `StpUtil.login(userId)`
7. 更新最近登录时间
8. 返回 token

### 5.5 改密码

1. 从 `UserContext` 取得当前 `userId`
2. 读取当前账号
3. 校验 `user_status=ACTIVE`
4. 校验旧密码
5. 更新新密码 hash
6. 更新 `password_updated_at`
7. 使该用户所有旧 token 失效
8. 返回成功

### 5.6 登出

1. 读取当前登录态
2. `StpUtil.logout()`
3. 返回成功

## 6. 防刷规则

### 6.1 发送验证码限流

- 同一手机号：60 秒 1 次
- 同一手机号：1 小时 5 次
- 同一手机号：24 小时 10 次
- 同一 IP：60 秒 5 次
- 同一 IP：24 小时 50 次

### 6.2 验证码规则

- 有效期：5 分钟
- 只能成功使用 1 次
- 必须按 `phone + bizType` 严格匹配，不能跨业务复用
- 连续输错 5 次，当前验证码作废
- 新验证码发出后，旧验证码直接失效

### 6.3 登录防爆破

- 密码登录同一手机号连续错 5 次，锁 15 分钟
- 验证码登录同一手机号连续错 5 次，锁 15 分钟

## 7. 错误语义

建议新增稳定业务码或稳定业务 `info`：

- `PHONE_ALREADY_REGISTERED`
- `PHONE_NOT_REGISTERED`
- `SMS_CODE_INVALID`
- `SMS_CODE_EXPIRED`
- `SMS_CODE_RATE_LIMITED`
- `LOGIN_PASSWORD_INVALID`
- `LOGIN_LOCKED`
- `ACCOUNT_DISABLED`

要求：

- 前端能直接按这些语义给出用户提示
- 不使用模糊的“系统异常”掩盖业务错误

## 8. 与现有代码的关系

### 8.1 删除项

必须删除：

- `nexus-api/src/main/java/cn/nexus/api/auth/IAuthApi.java`
- `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginRequestDTO.java`
- `nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthLoginResponseDTO.java`
- `nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`
- `nexus-trigger/src/test/java/cn/nexus/trigger/http/auth/AuthControllerTest.java`
- `WebMvcConfig` 中对 `/api/v1/auth/login` 的放行配置
- `UserContextInterceptor` 中从 `userId` / `X-User-Id` 解析身份的逻辑

### 8.2 保留项

保留并复用：

- `UserContext`
- `UserContextInterceptor`
- `user_base`
- `user_status`
- `ISocialIdPort`
- Sa-Token 登录态能力

其中 `UserContextInterceptor` 要调整为：

- 新匿名认证接口直接放行
- 受保护接口只从 token 解析 `userId`
- 不再提供 header 身份注入兜底

### 8.3 新增模块建议

建议新增：

- `nexus-api/.../auth/dto/*` 新 DTO
- `nexus-domain/.../auth/service/*`
- `nexus-domain/.../auth/adapter/repository/*`
- `nexus-domain/.../auth/adapter/port/*`
- `nexus-infrastructure/.../auth/repository/*`
- `nexus-infrastructure/.../auth/port/*`
- `nexus-infrastructure/.../dao/auth/*`
- `nexus-infrastructure/src/main/resources/mapper/auth/*`
- `nexus-trigger/.../http/auth/*` 新控制器

## 9. 破坏性变更

这是本设计里唯一主动接受的破坏性变更：

- 旧 `POST /api/v1/auth/login` 被删除

影响：

- 任何还在调用旧接口的前端、脚本、联调工具都会立即失效

必须同步完成：

- 前端改用新接口
- 自动化测试改用新接口
- 文档删掉“开发态登录”描述

## 10. 测试要求

必须覆盖：

- 注册成功
- 注册时手机号重复
- 注册后 `user_status` 默认值为 `ACTIVE`
- 发送验证码限流命中
- 验证码过期
- 验证码错误次数超限
- 验证码按 `bizType` 隔离
- 新验证码覆盖旧验证码后，旧验证码校验失败
- 同手机号多次发码时只认最后一条未使用验证码
- 密码登录成功
- 密码登录失败并锁定
- 验证码登录成功
- 验证码登录失败并锁定
- 改密码成功
- 旧密码错误
- 改密码后旧 token 失效
- 登出成功
- 删除旧登录后的路由与白名单收敛
- 新匿名认证接口未登录可达
- 受保护接口不再接受 `userId` / `X-User-Id` header 伪造身份

## 11. 验收标准

满足以下条件才算完成：

1. 代码里不再存在旧开发态登录接口和实现
2. 手机号成为唯一登录主键
3. `user_base` 不再承担认证职责，但仍承担 `username` 公开标识职责
4. 密码登录、验证码登录、注册、改密、登出全部可跑通
5. 短信发送和登录失败都有限流
6. 验证码只存 hash，不存明文
7. `UserContext` 能从新 token 正常拿到 `userId`
8. 新匿名认证接口可直接访问
9. 受保护接口不再接受 `userId` / `X-User-Id` header 伪造身份
10. 改密码后旧 token 不再可用
