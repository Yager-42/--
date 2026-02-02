# 用户领域实现方案（Profile / Settings / Status / user_base 读模型）
执行者：Codex（Linus mode）  
日期：2026-02-02  

> 这份文档的目标很简单：把“用户域”在本项目里做到一个正常社交平台水平，但不引入不必要的复杂度，也不破坏你现在已经跑通的社交/关系/风控/搜索链路。

---

## 0. 12 岁也能懂的版本（先把话说人话）

- 在社交平台里，“用户域”就是两件事：  
  1) 你的“名片”（昵称/头像/简介/背景图…）  
  2) 你的“开关”（隐私设置，比如关注要不要审批）  
- 本项目现在已经有一半了：  
  - 你是谁：从网关塞进来的 `X-User-Id`（服务端用 `UserContext` 读出来）  
  - 你的最小名片：`user_base`（给评论/通知/搜索补昵称头像）  
  - 一个隐私开关：`user_privacy_setting.need_approval`（关系域 follow 会用到）  
  - 你改昵称后，搜索索引要更新：`UserNicknameChangedEvent`（search 已在消费）  
- 这份方案要做的：把这半套补齐成“正常用户域”，并且跟现有代码对齐，开发照着做就能落地。

---

## 1. 需求理解确认（必须先对齐）

基于现有信息，我理解你的需求是：你要我写一个新的设计文档，给出“用户领域”的详细实现方案；该方案必须基于当前项目已实现的用户相关部分来设计，最终达到正常社交平台的用户域设计水平，并且能指导后续直接开发落地。  
请确认我的理解是否准确？

---

## 2. Linus 五层分析（用来避免你做出半吊子设计）

### 2.1 数据结构分析（核心是什么）

本项目里用户域的核心数据只有三块：

1) userId（真值）：来自网关 Header `X-User-Id`，服务端用 `UserContext` 读取。  
2) user_base（跨域读模型）：别的域（评论/通知/搜索）要批量补齐 nickname/avatar，不允许 N+1 单查。  
3) user_privacy_setting（设置）：关系域 follow 流程要用 `needApproval` 这种策略开关。

如果你把用户域做成“到处复制 userId + 到处发明昵称/头像来源”，系统会变成垃圾；所以必须把数据结构定死。

### 2.2 特殊情况识别（好代码没有特殊情况）

当前最大的特殊情况是：

- `UserBriefVO.nickname` 目前等同于 `user_base.username`（文档里写死了 “nickname = username”）  
  这不是业务规则，这是缺字段导致的补丁。

正常平台必须区分：

- `username`：唯一、用于 @username 提及（handle）  
- `nickname`：可改、用于展示/搜索（display name）

解决方案必须通过表结构消除这个特殊情况，而不是在代码里到处 if/else。

### 2.3 复杂度审查（别超过 3 层缩进）

用户域别上来就搞：

- 复杂画像/标签系统  
- 多级缓存一致性  
- 用户搜索/圈子/推荐  

这些都不是“补齐用户域”的第一优先级。先把名片与开关做扎实。

### 2.4 破坏性分析（Never break userspace）

本项目已经存在的用户可见契约（必须保持）：

- HTTP 层 userId 来源固定为 `UserContext.requireUserId()`（不改 Controller 方法签名）  
- search 链路已消费 `user.nickname_changed`（昵称改了索引要更新）  
- relation follow 流程依赖 `IRelationPolicyPort.needApproval(targetId)`

新增用户域不能把这些链路搞断；尤其是事件必须 after-commit 发，别制造“消费者读到未提交数据”的线上鬼故事。

### 2.5 实用性验证（理论输给现实）

现实约束很清楚：

- 你已经在用 Spring Boot + MyBatis + RabbitMQ + Redis  
- 你已经在用 DDD 分层（domain 只依赖 port/repository 接口）  
- 你已经接受 “安全不讨论” 的前提（userId 由网关注入当真值）

所以：用户域就按这个现实做，不要引入一个新体系把自己玩死。

---

## 3. 现状依据（当前项目“已实现的那一半”）

你不是凭空做用户域，你是基于这堆现成的事实继续设计：

### 3.1 userId 注入（HTTP 请求是谁）

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`：ThreadLocal 保存当前请求 userId（Header `X-User-Id`）  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java`：解析 Header 写入/清理 UserContext（放行 OPTIONS）  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/config/WebMvcConfig.java`：注册拦截器 `/api/v1/**`

### 3.2 user_base（跨域批量补齐昵称/头像）

- `project/nexus/docs/social_schema.sql`：`user_base` 表定义（目前字段：user_id/username/avatar_url）  
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserBriefVO.java`：最小展示信息  
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserBaseRepository.java`：批量查询接口（禁止 N+1）  
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserBaseRepository.java`：MyBatis 实现  
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/UserBaseMapper.xml`：SQL

**冲突点（当前实现 vs 本方案目标）**：  
1) `user_base` 目前没有 `nickname` 字段；而本方案要求 `username`（不可变 handle）与 `nickname`（可变展示名）分离。  
2) 领域 VO/仓储当前把 `nickname=username` 当成事实：  
   - `UserBriefVO` 注释写死 “nickname = username”  
   - `UserBaseRepository` 把 `UserBriefVO.nickname` 映射为 `UserBasePO.username`  
   - `UserBasePO`/`UserBaseMapper.xml` 也没有 `nickname` 可读  
这会直接导致：评论展示名、搜索索引 `authorNickname` 永远不可能变成“真实昵称”。  

### 3.3 user_privacy_setting（隐私开关：关注是否审批）

- `project/nexus/docs/user_privacy_setting.sql`：表定义  
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserPrivacyDao.java` + `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/UserPrivacyMapper.xml`：DAO + SQL  
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationPolicyPort.java`：策略端口（isBlocked/needApproval）  
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationPolicyPort.java`：策略实现（needApproval 读隐私表；isBlocked 委托黑名单端口）

**冲突点（当前实现 vs 本方案目标）**：  
`IUserPrivacyDao` 目前只有 `selectByUserId`（只读），没有对应的 upsert/update 写入能力。也就是说：关系域已经依赖了“关注是否审批”的开关，但用户域目前还没有正式写入口把这个开关改起来。  

### 3.4 昵称变更事件（搜索索引旁路更新）

- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/UserNicknameChangedEvent.java`：事件契约  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`：消费昵称变更并调用 `ISearchEnginePort.updateAuthorNickname(...)`  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`：队列命名/绑定（包含 `Q_USER_NICKNAME_CHANGED`）

**冲突点（当前实现 vs 本方案目标）**：  
1) Search 侧已有队列绑定与消费者，但当前代码库里缺少事件生产者（没有任何地方向 exchange=`social.feed`、rk=`user.nickname_changed` 投递 `UserNicknameChangedEvent`）。  
2) 即使未来补齐 producer，如果 `user_base.nickname` 仍不存在，search consumer 回表拿到的 “nickname” 依旧等同 `username`，索引更新会变成自欺欺人。  

### 3.5 风控用户状态（能力开关，不等于账号域）

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`：`userStatus(userId)` 推导 `POST/COMMENT` 能力  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`：`/api/v1/risk/user/status` 入口

---

## 4. 目标与非目标（大厂常见拆分，但别发疯）

### 4.1 目标（要做到）

用户域在本项目内提供四类能力：

1) Profile（名片）：本次只做 nickname/avatarUrl（bio/backgroundImage/gender 延后）  
2) Settings（开关）：隐私设置（关注是否审批等）  
3) Status（状态）：ACTIVE/DEACTIVATED 之类的最小状态（封禁等仍以 risk 为准）  
4) ReadModel（给别的域用的“最小用户信息”）：`user_base` 批量查询，供评论/通知/搜索补全

并且：

- 更新昵称要写入 Outbox，并在 after-commit 尝试投递 `UserNicknameChangedEvent`（search 已消费；见第 10.4 节）  
- 批量查询要避免 N+1（已在 `IUserBaseRepository` 强制要求）  
- 不破坏现有 HTTP 契约：继续用 `UserContext.requireUserId()` 取当前用户

### 4.2 非目标（明确不做）

为了避免你 INFP “理想化预期 + 过度打磨” 直接把项目拖死，以下明确不做：

- 账号/认证（登录/注册/密码/短信/SSO）：本项目不讨论，继续假设网关已给出 userId 真值  
- 用户搜索（搜用户/圈子）：本次用户域不做  
- 复杂画像/标签系统：不做  
- 多级缓存一致性工程：先不做（可选优化）

---

## 5. 领域边界（建议按“账号中心 vs 用户档案”拆）

大厂通用拆法（不需要你记术语，你只要记职责）：

- Account（账号中心）：负责“谁能登录、是谁、凭证是什么”（本项目不做）  
- User Profile（用户档案）：负责“对外展示的名片”和“用户设置”（本项目要做）  
- Relation（关系域）：关注/粉丝/好友/拉黑（本项目已做）  
- Risk（风控域）：封禁/处罚/能力开关（本项目已做）  
- Search（搜索旁路）：索引与召回（本项目已做 POST 搜索；昵称变更索引刷新已接）

本项目的 User Domain 只负责：Profile/Settings/Status + user_base 读模型。

---

## 6. 目标设计：模块与代码落点（沿用现有分层）

原则：domain 不直接依赖 MyBatis/RabbitMQ 客户端；只依赖 port/repository 接口。实现放 infrastructure。

### 6.1 新增/调整的包结构（建议）

- `nexus-domain`：新增 `cn.nexus.domain.user.*`  
  - `model/entity`：User/Profile/Privacy/Status  
  - `model/valobj`：UserBriefVO（可复用现有或迁移）  
  - `adapter/repository`：User 仓储接口（读写）  
  - `adapter/port`：事件 Outbox 端口 `IUserEventOutboxPort`（昵称变更等事件落库+重试投递）  
  - `service`：UserService（更新 profile/settings/status）

- `nexus-infrastructure`：新增 `cn.nexus.infrastructure.adapter.user.*` + `dao/user/*` + `resources/mapper/user/*`  
  - MyBatis DAO/PO/Mapper.xml  
  - Outbox DAO/Mapper + Publisher（复用 RabbitTemplate；定时重试对齐 `RelationEventRetryJob`）

- `nexus-api`：新增 `cn.nexus.api.user.*`  
  - `IUserProfileApi` / `IUserSettingApi` + DTO

- `nexus-trigger`：新增 `cn.nexus.trigger.http.user.*` Controller  
  - Controller 实现 nexus-api 接口；用 UserContext 获取当前 userId

- `nexus-types`：事件类型尽量放这里（已存在 `UserNicknameChangedEvent`）

**现实约束（避免你为了“洁癖”把项目搞崩）**：  
- 当前 `user_base / UserBriefVO / IUserBaseRepository` 都落在 `cn.nexus.domain.social.*` 下，且评论/搜索/通知已经依赖它。  
- 所以短期不要做“搬家式重构”（改包名/挪接口）来追求纯粹；先把数据结构与读写语义对齐（nickname 字段、昵称变更事件生产者、隐私设置写入口），让所有域一起变正确。  
- 真要抽离 `cn.nexus.domain.user.*`，也应该是“渐进迁移”：先保持接口稳定 → 再迁包/替换调用方；否则就是自找破坏性变更。  

---

## 7. 数据模型与表结构（消除“nickname=username”的特殊情况）

### 7.1 核心字段定义（写死）

- `userId`：Long；来源 `X-User-Id`；全局唯一，不可变  
- `username`：唯一 handle，用于 `@username` 提及（全局唯一，禁止修改）  
- `nickname`：展示名（可改、不要求唯一）  
- `avatarUrl`：头像 URL  

本次 Profile 范围（你已拍板：2A）：只做 `nickname` + `avatarUrl`。其它字段（bio/background/gender）延后，不进入本次 DDL/API。

补充约束（必须对齐现有实现）：

- `@username` 提及解析已经在评论链路里落地：`InteractionService` 会从评论内容里解析 `@xxx`，并通过 `IUserBaseRepository.listByUsernames(...)` 把 `username -> userId` 映射出来，然后发布 `COMMENT_MENTIONED` 通知事件。  
  - 相关代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- 所以 `username` 必须满足：全局唯一 + 一旦写入不可变（你已拍板“不允许修改”）。

数据库约束（把“区分大小写”变成事实，你已拍板：3A）：

```sql
-- 让 username 的比较与唯一索引都真正区分大小写（否则 3A 就是假的）
ALTER TABLE user_base
  MODIFY COLUMN username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

CREATE UNIQUE INDEX uk_user_base_username ON user_base (username);
```

### 7.2 表 1：user_base（建议升级为“用户基础名片表”）

现状：`project/nexus/docs/social_schema.sql` 里只有 user_id/username/avatar_url。  
问题：nickname 缺失导致特殊情况（nickname=username）。

建议：在不破坏现有读链路的前提下扩展字段（增量迁移）：

```sql
ALTER TABLE user_base
  ADD COLUMN nickname VARCHAR(64) DEFAULT '' COMMENT '展示昵称（可改）' AFTER username;

UPDATE user_base SET nickname = username WHERE nickname = '' OR nickname IS NULL;
```

本次你已拍板（2A + 13.1#1）：只加 `nickname`（`avatar_url` 已存在）；bio/background/gender 延后。

### 7.3 表 2：user_privacy_setting（保持不变，归入 Settings）

现状已有：`project/nexus/docs/user_privacy_setting.sql` 与对应 MyBatis DAO/Mapper。  
最小版本只保留：`need_approval`（关注是否需要审批）。

### 7.4 表 3：user_status（新增：最小状态）

理由：正常平台需要“用户注销/停用”的最小状态；但封禁/能力开关仍以 risk 为准，不在这里重复实现一套。

```sql
CREATE TABLE IF NOT EXISTS `user_status` (
  `user_id` BIGINT NOT NULL,
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/DEACTIVATED',
  `deactivated_time` DATETIME NULL,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户状态表（最小实现）';
```

---

## 8. 领域模型（DDD 视角，但不写玄学）

### 8.1 聚合：UserAggregate（建议）

聚合内包含三块：

- Profile：来自 `user_base`（nickname/avatarUrl）  
- Settings：来自 `user_privacy_setting`  
- Status：来自 `user_status`

聚合边界（简单规则）：

- 修改昵称/头像：写 `user_base`  
- 修改隐私：写 `user_privacy_setting`  
- 注销/停用：写 `user_status`  
- 修改昵称成功后：after-commit 发布 `UserNicknameChangedEvent`

### 8.2 仓储与端口（建议接口）

- 复用 `IUserBaseRepository`（已存在）：批量读 user_base（供跨域读侧）  
- 新增 `IUserProfileRepository`：读写当前用户 profile（本质还是 user_base 的单用户写接口）  
- 新增 `IUserPrivacyRepository`：读写隐私设置  
- 新增 `IUserStatusRepository`：读写 user_status  

事件分发端口（对齐内容域的模式）：

事件 Outbox 端口（已拍板：必须落库补偿，见第 10.4 节）：

- 新增 `IUserEventOutboxPort`：封装 `user_event_outbox` 的写入与重试投递（投递目标：exchange=`social.feed`，routingKey=`user.nickname_changed`）

---

## 9. 对外接口（HTTP 契约，按现有风格）

原则：Controller 一律从 `UserContext.requireUserId()` 获取当前 userId；客户端不允许“自称我是谁”。

### 9.1 Profile API（名片）

建议新增接口（nexus-api）：

- `GET /api/v1/user/me/profile`：获取我的 profile  
- `GET /api/v1/user/profile?targetUserId=...`：获取他人 profile  
- `POST /api/v1/user/me/profile`：更新我的 profile（nickname/avatarUrl）

DTO 设计要点：

- 更新请求是 Patch：`null=不改`，`""=清空`（仅 `avatarUrl` 允许清空；`nickname` 空白直接报错）  
- nickname 发生变化才触发事件（走 Outbox，见第 10.4 节）

### 9.2 Settings API（开关）

- `GET /api/v1/user/me/privacy`：获取我的隐私设置  
- `POST /api/v1/user/me/privacy`：更新隐私设置（目前只 needApproval）

### 9.3 Profile Page 聚合接口（可选，但社交平台基本都需要）

如果你要“像大厂那样”的个人主页，通常会聚合：

- user_base：nickname/avatarUrl  
- relation：followCount/followerCount/isFollow/friendCount  
- content：作品数/喜欢数（若已有统计口径）  
- risk：capabilities（是否能发帖/评论）

这个聚合建议放在应用层的 Query Service，不要把关系/风控逻辑塞进用户聚合里。

### 9.4 网关同步写入口（update-only，不负责创建）

你已拍板（1B）：本服务 **不自动创建** `user_base` 行；该接口只做“同步更新”（update-only）。  
如果 `user_base` 行不存在，直接返回 NOT_FOUND，让上游修“用户初始化/落库”流程（别在这里打补丁自嗨）。  
这个接口是“系统写入口”，**不是**给业务客户端用的。

建议新增接口（nexus-api，给网关调用）：

- `POST /api/v1/internal/user/upsert`

请求字段建议（最小可用）：

- `userId`：必填（账号中心分配的用户 ID）  
- `username`：必填（唯一 handle；区分大小写；后续不允许变更，仅用于一致性校验）  
- `nickname/avatarUrl`：可选（允许更新）  
- `needApproval`：可选（不传则不改；默认值 false）  
- `status`：可选（不传则不改；默认 ACTIVE）

行为规则（写死，避免后续互相扯皮）：

- 若 `userId` 不存在：直接返回 NOT_FOUND（不自动创建）。  
- 若 `userId` 已存在：只允许更新可变字段（nickname/avatarUrl）；若传入的 `username` 与库中不一致，直接返回错误（建议 CONFLICT；别 silently ignore，否则你会制造无法追踪的数据不一致）。  
- 若本次同步导致 nickname 变化：事务内写入 Outbox；提交后尝试投递 `UserNicknameChangedEvent`（用于搜索索引刷新，见第 10.4 节）。

pseudocode（核心逻辑，不写细节代码）：

```
syncUpdateFromGateway(req):
  assertTx()
  existed = userBaseRepo.get(req.userId)
  if existed == null: return NOT_FOUND
  if req.username != existed.username: return CONFLICT

  update mutable fields in user_base
  upsert privacy/status if provided

  if nickname changed:
    outbox.save(UserNicknameChangedEvent(userId, tsMs))
    dispatchAfterCommit(() => outbox.tryPublishPending())
```

---

## 10. 事件设计（复用现有 MQ 拓扑，别另起炉灶）

### 10.1 事件与路由（写死）

- exchange：`social.feed`  
- routingKey：`user.nickname_changed`  
- event class：`UserNicknameChangedEvent { userId, tsMs }`  
  - 文件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/UserNicknameChangedEvent.java`

注意：这里的 `nickname` 指“展示昵称”（display name），不是 `username`（唯一 handle）。  
你已拍板的是 `username` 不可变；`nickname` 仍然允许变更，所以这个事件依然有意义：用于让 search 刷新索引里冗余的 `authorNickname`。

### 10.2 生产者（用户域写入后 after-commit）

对齐内容域 `ContentService` 的规则：只有事务提交成功后才发事件。  
建议新增服务文件位置：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/user/service/UserService.java`

pseudocode（关键点）：  

```
updateProfile(userId, req):
  assertTx()
  before = profileRepo.get(userId)  // 从 user_base 读
  profileRepo.update(userId, req)  // 写 user_base

  if req.nickname is not null AND req.nickname != before.nickname:
    outbox.save(UserNicknameChangedEvent(userId, tsMs))
    dispatchAfterCommit(() => outbox.tryPublishPending())

  return success
```

### 10.3 消费者（search 已存在）

现状消费者已实现：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`

它会：

1) 收到 `UserNicknameChangedEvent`  
2) 回表 `user_base` 读 nickname  
3) 调用 `ISearchEnginePort.updateAuthorNickname(userId, nickname)` 做 update-by-query

所以你只需要保证：

- user_base 里真的有 nickname（别再靠 username 冒充）  
- 事件 after-commit 发

### 10.4 Outbox（已拍板：必须落库补偿）

目标：不允许出现“Profile 已改成功，但 `user.nickname_changed` 丢了”的情况。  
做法：事务内先落库 outbox；提交后尝试立即投递；失败留库，定时任务重试。

#### 10.4.1 表结构（建议新增：user_event_outbox）

```sql
CREATE TABLE IF NOT EXISTS `user_event_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(64) NOT NULL COMMENT '例如: user.nickname_changed',
  `fingerprint` VARCHAR(128) NOT NULL COMMENT '去重键（建议: event_type:userId:tsMs）',
  `payload` TEXT NOT NULL COMMENT 'JSON',
  `status` VARCHAR(16) NOT NULL COMMENT 'NEW/DONE/FAIL',
  `retry_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_event_outbox_fingerprint` (`fingerprint`),
  KEY `idx_user_event_outbox_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户域事件 Outbox（昵称变更等）';
```

#### 10.4.2 写入与投递规则（写死）

- 事务内：nickname 发生变化时，先 `INSERT IGNORE` 写入 outbox（`status=NEW`）。  
- after-commit：尝试投递 NEW/FAIL；成功 `markDone`；失败 `markFail`（等待重试）。  
- 定时重试：每分钟拉取 FAIL（limit=100）逐条投递；每日清理 DONE 7 天前记录（对齐 `RelationEventRetryJob` 的风格）。  

备注：search 侧 update-by-query 是幂等的；重复投递最多浪费一次更新，不会破坏用户可见行为。

---

## 11. 分阶段落地计划（别一次性做完把自己累死）

### 阶段 A：把“数据结构”先做对（1-2 天）

交付：

- `user_base` 增加 `nickname` 并回填 `nickname=username`  
- `user_base.username` 真正区分大小写 + 唯一索引（见 7.1 SQL）  
- 新增 `user_status`  
- 新增 `user_event_outbox`

验收：

- `user_base` 任意一条记录都有 nickname（不允许为空）

### 阶段 B：补齐用户域写接口 + 事件（1-2 天）

交付：

- `POST /api/v1/user/me/profile`：能更新 nickname/avatarUrl；nickname 变化写入 outbox，after-commit 尝试投递 `UserNicknameChangedEvent`（第 10.4 节）  
- `POST /api/v1/user/me/privacy`：能更新 needApproval
- `POST /api/v1/internal/user/upsert`：网关可同步更新用户基础信息与默认设置（update-only；缺行=NOT_FOUND；username 必须一致）

验收：

- 改昵称后，search 消费者日志能看到 nickname_update（或索引字段更新）  
- follow 流程能受 needApproval 影响（无需改 relation 代码）

### 阶段 C：补齐读接口（1 天）

交付：

- `GET /api/v1/user/me/profile`  
- `GET /api/v1/user/profile?targetUserId=...`  
- （可选）个人主页聚合接口

验收：

- 读接口返回字段齐全；跨域补全不出现 N+1

### 阶段 D：性能优化（可选，后续）

交付：

- user_base Redis 缓存（读多写少时再做）

验收：

- 压测或线上指标证明 DB 压力下降；缓存击穿/回源策略简单可控

---

## 12. 验收清单（达到“正常社交平台用户域水平”）

你最终应当能做到：

- username（唯一 handle）与 nickname（展示名）分离，不再靠补丁 `nickname=username`  
- 有完整的 profile 更新与读取接口（我/他人）  
- 有至少一个隐私设置（needApproval）并且被关系域真正使用  
- 有最小用户状态（ACTIVE/DEACTIVATED）  
- 昵称变更事件 after-commit 发出，并被 search 链路消费更新索引  
- user_base 批量读接口保持批量语义（禁止 N+1）

---

## 13. 已拍板（你的选择，后续实现不得偏离）

1) `username`：禁止修改（handle 不可变）。  
2) 用户同步：本服务提供 internal 同步更新入口给网关调用（update-only；缺行直接 NOT_FOUND；见 `POST /api/v1/internal/user/upsert`）。
3) `nickname`：允许修改（展示名）；一旦变更必须 after-commit 发布 `user.nickname_changed` 供 search 刷新索引。

### 13.1 已拍板：实现选项（实现者不得自由发挥）

> 说明：以下是你在评审阶段对“缺漏项”的逐条拍板。后续实现必须以本节为准；若本节与其它章节冲突，以本节为准。

1) user_base 扩字段范围：只加 `nickname`（avatar_url 已存在；bio/background/gender 延后）。  
2) nickname 空白输入：空白直接报错（不支持“清空昵称”；也不支持“重置为 username”）。  
3) username 大小写：区分大小写（case-sensitive）。  
4) username 不可变：仅在应用层强校验（upsert 发现不一致直接报错）。  
5) 缺行策略：`user_base` 行不存在视为错误（不自动创建）。  
6) internal upsert：先 SELECT 校验 username，再 UPDATE；影响行数=0 视为 NOT_FOUND（禁止 ON DUPLICATE KEY INSERT）。  
7) profile 更新语义：Patch（不传就不改）。  
8) 清空字段语义：`null=不改`，`\"\"=清空`（注意：nickname 例外，按 2) 执行，空白一律报错）。  
9) 错误码：扩充 `ResponseCode`（最少补齐 NOT_FOUND/CONFLICT/USER_DEACTIVATED 等）。  
10) API 拆分：拆 `IUserProfileApi` + `IUserSettingApi` + `IUserInternal*Api`。  
11) 他人 Profile URL：`GET /api/v1/user/profile?targetUserId=...`。  
12) 看他人 Profile：需要做屏蔽/关系隐私校验（最小：任一方向屏蔽即返回 NOT_FOUND）。  
13) 昵称变更索引刷新：用 MQ 事件 `user.nickname_changed`（search 已消费）。  
14) 事件 payload：只发 `{userId, tsMs}`，消费者回表读 nickname。  
15) after-commit：使用 `TransactionSynchronizationManager.registerSynchronization(...afterCommit...)`。  
16) 事件可靠性：使用 Outbox（落库 -> after-commit 尝试投递 -> 失败可重试），禁止 best-effort 只 log。  
17) user_privacy_setting 写入：upsert（不存在则插入默认行）。  
18) user_status：DEACTIVATED 只拦“写请求”不拦“读请求”（建议统一在 HTTP 入口层拦截；internal 接口例外允许写）。

---

## 14. 跨领域对齐（其他领域如何使用用户域，必须统一）

这一节的目的只有一个：把“别的域怎么用用户信息”定死，避免每个域各写各的补丁。

### 14.1 HTTP：userId 统一入口（不改现有契约）

- 现状：HTTP 请求 userId 由 `UserContext` 注入（Header `X-User-Id`）。  
  - 相关代码：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`
- 对齐要求：所有面向客户端的 Controller 都用 `UserContext.requireUserId()`；DTO 里不允许客户端自带 userId 字段当真值。

### 14.2 评论读侧：批量补全 nickname/avatar（禁止 N+1）

- 现状：评论列表会批量收集 userIds，然后调用 `IUserBaseRepository.listByUserIds(...)` 补全 `CommentViewVO.nickname/avatarUrl`。  
  - 相关代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java`
- 对齐要求：  
  - `user_base` 必须提供正确的 `nickname/avatarUrl`（因此必须落地 `nickname` 字段，不再用 username 冒充）。  
  - `IUserBaseRepository` 必须保持“批量接口”语义（不允许改成循环单查）。
  - 为了让“所有依赖 user_base 的域”同时变正确，`user_base` 的读取实现需要同步对齐（别指望调用方改）：  
    - MyBatis SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/UserBaseMapper.xml`（SELECT 增加 nickname 列）  
    - PO：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserBasePO.java`（增加 nickname 字段）  
    - 仓储实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserBaseRepository.java`（VO.nickname 取 nickname；迁移期可 fallback 到 username）

### 14.3 评论链路：@username 提及解析（username -> userId 映射）

- 现状：评论创建会解析内容里的 `@xxx`，并通过 `IUserBaseRepository.listByUsernames(...)` 映射到 userId，再发布 `COMMENT_MENTIONED` 通知事件。  
  - 相关代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- 对齐要求：  
  - `username` 全局唯一且不可变（否则历史评论里的 @username 会指向错误的人）。  
  - 上游初始化 `user_base` 时必须写入 username；internal 同步更新只做一致性校验：后续若 username 不一致必须报错并让上游修正。

### 14.4 搜索旁路：authorNickname 冗余字段刷新

- 现状：search 消费 `UserNicknameChangedEvent` 后，会回表 `user_base` 取 nickname，然后执行 update-by-query 更新索引冗余字段。  
  - 相关代码：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`
- 对齐要求：  
  - 任何导致 nickname 变化的写入口（包括“用户自己改昵称”和“网关 upsert 同步昵称”）都必须 after-commit 发布 `user.nickname_changed`。  
  - `user_base` 的查询实现要改为读取真实 nickname（否则你永远更新不到正确值）。
  - 备注：search 消费者读取 nickname 的路径同样走 `IUserBaseRepository`，所以只要你把 `UserBaseRepository` 对齐好了，search/评论/@mention 三条链路会一起变正确（这才叫“好品味”）。

### 14.5 关系域：关注审批策略（needApproval）由用户域维护

- 现状：follow 流程通过 `IRelationPolicyPort.needApproval(targetId)` 判断是否进入待审批；端口实现读取 `user_privacy_setting`。  
  - 相关代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`、`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationPolicyPort.java`
- 对齐要求：  
  - `user_privacy_setting` 的写入口只能在用户域（`POST /api/v1/user/me/privacy` 或网关 upsert）发生；关系域只读，不要搞双写。  

### 14.6 风控域：能力开关与封禁不在用户域重复实现

- 现状：`RiskService.userStatus(userId)` 返回 capabilities（POST/COMMENT 等）。  
  - 相关代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
- 对齐要求：  
  - 用户域不维护“封禁/可发帖”等风控能力字段；需要展示时由个人主页聚合接口调用 risk 服务获取（避免两套状态打架）。

---

## 15. 冲突点补齐与解决方法（把“方案”落到代码事实）

这一节只干一件事：把你问的“冲突点”写死，并给出“怎么修”的最小路径。  
原则：**先修数据结构，再修事件生产，再补写入口**；不要在业务层堆 if/else 掩盖上游缺字段。

### 15.1 冲突：user_base 缺少 nickname，导致各域把 nickname=username 当成事实

**现状证据（代码/DDL）**：  
- 表结构没有 nickname：`project/nexus/docs/social_schema.sql`  
- VO 注释写死 “nickname = username”：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserBriefVO.java`  
- 仓储映射 nickname=po.username：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserBaseRepository.java`  
- PO/SQL 也没有 nickname：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserBasePO.java`、`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/UserBaseMapper.xml`

**影响（跨域会一起坏）**：  
- 评论列表展示名永远跟 username 一样（用户改昵称也没意义）。  
- 搜索索引 `authorNickname` 永远无法变成“展示昵称”，`user.nickname_changed` 就算触发也只能回写 username。  

**解决方法（按顺序，不要跳步）**：  
1) DDL：给 `user_base` 增 `nickname` 并回填（最小必须做到“非空且有值”）：见本方案 `7.2 user_base` 的 SQL。  
2) infrastructure：同步扩展 `UserBasePO` / `UserBaseMapper.xml` / `UserBaseRepository`：  
   - SQL select 增加 nickname 列  
   - PO 增加 nickname 字段  
   - Repository 映射：`VO.nickname = po.nickname`；迁移期允许 fallback：nickname 为空则用 username（只允许这一处 fallback，别扩散到调用方）  
3) domain/文档：把 `UserBriefVO` 注释里的 “nickname=username” 改成 “迁移期 fallback，最终以 nickname 为准”，避免继续误导后续开发。

**验收（你真的修对了才算）**：  
- 评论列表 `nickname` 能显示 nickname（不再等于 username）。  
- 搜索索引写入的 `authorNickname` 使用 nickname（见 `SearchIndexConsumer` 的 resolveNickname 路径）。  

### 15.2 冲突：user.nickname_changed 已有 consumer/binding，但缺少 producer（事件不会凭空出现）

**现状证据**：  
- Search 侧已绑定 routingKey：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`（`RK_USER_NICKNAME_CHANGED=user.nickname_changed`）  
- Search 侧已消费并 update-by-query：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`  
- 但代码库中没有任何地方像 `ContentDispatchPort` 那样投递该事件到 `social.feed`：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`

**解决方法（复用现有风格，别另起炉灶）**：  
1) 新增端口：`IUserEventOutboxPort`（domain 只依赖端口）。  
2) 按已拍板（Outbox，第 10.4 节）：事务内先落库 `user_event_outbox`；提交后尝试投递；失败可重试。  
3) 在“任何可能改变 nickname 的写入口”里触发（两类入口）：  
   - 用户自助改名：`POST /api/v1/user/me/profile`  
   - 网关同步更新（update-only）：`POST /api/v1/internal/user/upsert`  

pseudocode（关键点：after-commit，别提前发）：

```
updateNicknameTx(userId, newNickname):
  assertTx()
  old = load user_base(userId)
  update user_base set nickname=newNickname
  if old.nickname != newNickname:
    outbox.save(UserNicknameChangedEvent(userId, tsMs))
    afterCommit(() => outbox.tryPublishPending())
```

**验收**：  
- 改昵称后，Search consumer 能打印 `event=search.index.nickname_update`（见 `SearchIndexConsumer` 日志）。  

### 15.3 冲突：user_privacy_setting 当前只有读，没有写（关注审批开关“永远改不了”）

**现状证据**：  
- `IUserPrivacyDao` 只有 `selectByUserId`：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserPrivacyDao.java`  
- 关系域已经在读它：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationPolicyPort.java`

**解决方法**：  
1) 给 `IUserPrivacyDao` + `UserPrivacyMapper.xml` 增加 upsert/update（最小：按 user_id 更新 need_approval）。  
2) 用户域提供写入口：`POST /api/v1/user/me/privacy`（当前用户改自己的 needApproval）。  
3) 网关 upsert 也允许写入 needApproval（作为系统同步入口）。  
4) 关系域继续只读（`IRelationPolicyPort` 不变），避免双写打架。

**验收**：  
- target 用户打开 needApproval 后，follow 返回状态变成 PENDING（`RelationService.follow` 走 `needApproval(targetId)` 分支）。  

### 15.4 冲突：部分 HTTP 接口仍存在 userId 参数（但实现上会忽略）

**现状证据**：  
`ContentController.publishAttempt` 仍保留 `@RequestParam("userId") Long ignoredUserId`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

**解决方法（不破坏现有用户可见行为）**：  
- 文档写死：这类 userId 参数只为兼容旧客户端存在，**服务端永远以 `UserContext.requireUserId()` 为准**；参数即使传入也被忽略。  
- 新增用户域接口时，不再设计任何“客户端传 userId 当真值”的 DTO 字段。

### 15.5 冲突：用户域“应该独立” vs 现状代码归属在 social 包下

**现状**：用户相关读模型（`IUserBaseRepository`/`UserBriefVO`/user_base）目前在 `cn.nexus.domain.social.*`。  

**解决方法（推荐路径：先正确，再优雅）**：  
1) 短期：不迁包、不改既有接口；按 15.1/15.2/15.3 把数据结构与写入口补齐，让所有依赖一起变正确。  
2) 中期：如果你坚持要抽 `cn.nexus.domain.user.*`，也必须做到“接口稳定迁移”：先引入 user 域 service/写接口，但复用现有 `IUserBaseRepository`；等调用方收敛后再做包迁移。  
