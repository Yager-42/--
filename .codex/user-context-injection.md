# userId 注入统一方案（网关上下文 → 服务端 UserContext）

执行者：Codex（Linus mode）  
日期：2026-01-14  

目标：把“当前请求是谁（userId）”的来源定死，避免业务实现里到处发明 `userId` 字段/参数来源，导致幂等、计数、权限语义全崩。

## 0. 已拍板（实现不得偏离）

- `userId` 一律从 **登录态/网关上下文注入** 获取。
- 不允许客户端在 DTO / Query 里自己传 `userId`（否则“我是谁”根本不可信）。
- 不做任何安全校验/签名校验：把网关注入的值当真值（安全不在本项目讨论范围）。

## 1. 网关契约（你只需要这一条）

- 每个请求携带 Header：`X-User-Id: <Long>`

## 2. 服务端契约（另一个 Codex agent 可直接照抄实现）

### 2.1 为什么必须做成 UserContext

本仓库的 Controller 都实现了 `nexus-api` 里的接口（方法签名固定），你没法在方法参数里随便加 `@RequestHeader("X-User-Id") Long userId`。

所以必须提供一个 **不改方法签名也能取到 userId** 的统一入口：`UserContext.requireUserId()`。

### 2.2 建议落点与接口

- 文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`
- 方法（硬契约）：
  - `Long requireUserId()`：取不到就直接抛错（别写 fallback，问题就应该暴露）

pseudocode（实现要点）：

```
requireUserId():
  request = RequestContextHolder.currentRequest()
  raw = request.getHeader("X-User-Id")
  if raw is blank: throw AppException(ResponseCode.ILLEGAL_PARAMETER)
  return Long.parseLong(raw)
```

## 3. Controller 使用方式（不改变 DTO，但不相信 DTO）

### 3.1 Interaction（点赞/评论）

- 取 `userId = UserContext.requireUserId()`
- 调用 domain service 时把 `userId` 作为入参传下去
- DTO 里不新增 `userId` 字段（`ReactionRequestDTO/CommentRequestDTO` 都不改）

### 3.2 Feed（timeline/profile/负反馈）

为了保持契约不变，DTO 里 `userId/visitorId` 字段可以继续存在，但 **Controller 必须忽略** 客户端传入值：

```
userId = UserContext.requireUserId()
feedService.timeline(userId, requestDTO.cursor, requestDTO.limit, requestDTO.feedType)
```

## 4. 非 HTTP 场景（MQ/Job）

- `UserContext` 只对 HTTP 请求有效。
- MQ/Job 需要的 `userId` 必须来自消息体/任务参数（不要在 Consumer 里“想办法猜 userId”）。

