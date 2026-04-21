# Nexus 后端接口文档（给前端对接）

> 生成来源：代码扫描（`project/nexus/nexus-trigger` 里所有 `@RestController`）。
> 更新/校验：`python project/nexus/docs/tools/gen_frontend_api_doc.py`
> - 会自动刷新第 3 节“DTO 字典”，并检查第 1 节“接口总表”是否覆盖所有对外接口。
> 如果你说的“项目”不是 `project/nexus`，把目录名告诉我，我会重新扫描。

## 0) 你需要先知道的 5 件事

1. **默认后端地址**：`http://localhost:8080`（来自 `nexus-app/src/main/resources/application.yml`）。
2. **统一响应壳**（大多数接口）：`{ code, info, data }`，`code == "0000"` 才算成功；很多失败也会是 HTTP 200，所以前端一定要看 `code`。
3. **鉴权方式**：请求头带 `Authorization: Bearer <token>`。
4. **匿名可调用接口**：健康检查 + 4 个登录相关接口（下方会列出来）。
5. **管理员接口**：带 `@SaCheckRole("ADMIN")` 的接口必须是管理员 token。

### 0.1 统一响应格式（Response<T>）

```json
{
  "code": "0000",
  "info": "成功",
  "data": {}
}
```

常见 `code`（代码：`nexus-types/src/main/java/cn/nexus/types/enums/ResponseCode.java`）：

- `0000` 成功
- `0001` 未知失败
- `0002` 非法参数
- `0404` 资源不存在
- `0409` 数据冲突
- `0410` 用户已停用

### 0.2 鉴权（什么时候需要 token）

- 规则：只要路径是 `/api/v1/**`，默认都需要 token；除非在白名单里。
- 重要提醒：需要登录的接口，后端会从 token 里解析 `userId`。即使你在参数里看到 `userId/visitorId/targetId` 这类字段，多数情况下后端也会忽略它们（以 token 为准）。但如果某个接口把它做成 **必填 Query 参数**，你仍然要按要求传（通常传当前登录用户的 id）。
- 白名单（无需 token）：
  - `/api/v1/auth/login/password`
  - `/api/v1/auth/register`
  - `/api/v1/health`
  - `/api/v1/health/**`

### 0.3 前端最小调用示例（建议直接复制）

下面是一个最小的 `fetch` 封装：统一加 token，统一判断 `code`。

```ts
type ApiResponse<T> = { code: string; info: string; data: T };

const BASE_URL = "http://localhost:8080";

function getToken() {
  return localStorage.getItem("token") || "";
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  const json = (await res.json()) as ApiResponse<T>;
  if (json.code !== "0000") throw new Error(json.info || json.code);
  return json.data;
}
```

文件上传（`multipart/form-data`）示例：

```ts
type ApiResponse<T> = { code: string; info: string; data: T };

const BASE_URL = "http://localhost:8080";

export async function uploadFile(file: File): Promise<string> {
  const fd = new FormData();
  fd.append("file", file);

  const res = await fetch(`${BASE_URL}/file/upload`, { method: "POST", body: fd });
  const json = (await res.json()) as ApiResponse<{ url: string }>;
  if (json.code !== "0000") throw new Error(json.info || json.code);
  return json.data.url;
}
```

### 0.4 常见页面怎么串接口（不迷路）

- 登录/注册
  - 注册：`POST /api/v1/auth/register`
  - 登录：`POST /api/v1/auth/login/password`
  - 拿当前登录信息：`GET /api/v1/auth/me`
- 首页/关注流
  - 拉时间线：`GET /api/v1/feed/timeline`
  - 点赞/收藏/评论等动作：`POST /api/v1/interact/reaction`、`POST /api/v1/interact/comment`
- 内容详情页
  - 内容详情：`GET /api/v1/content/{postId}`
  - 热门评论：`GET /api/v1/comment/hot`
  - 评论列表：`GET /api/v1/comment/list`、`GET /api/v1/comment/reply/list`
- 发布内容（图文/视频）
  - 上传：`POST /api/v1/media/upload/session`（推荐直传）或 `POST /file/upload`（兼容）
  - 草稿：`PUT /api/v1/content/draft`、`PATCH /api/v1/content/draft/{draftId}`
  - 发布：`POST /api/v1/content/publish`
- 搜索
  - 联想：`GET /api/v1/search/suggest`
  - 搜索：`GET /api/v1/search`

如果你在接口详情里看到某个 `XXXDTO`，但不知道里面有哪些字段，直接看第 3 节“DTO 字典”。

### 0.5 常用枚举值（前端要显示/判断时用）

- `mediaType`（内容媒体类型）：
  - `0` 纯文（TEXT）
  - `1` 图文（IMAGE）
  - `2` 视频（VIDEO）
- `status`（内容状态）：
  - `0` 草稿（DRAFT）
  - `1` 审核中（PENDING_REVIEW）
  - `2` 已发布（PUBLISHED）
  - `3` 审核拒绝（REJECTED）
  - `6` 删除（DELETED）
- `visibility`（可见性）：
  - `0` 公开（PUBLIC）
  - `2` 仅自己可见（PRIVATE）

## 1) 接口总表（可直接当对接清单）

| 方法 | 路径 | 需登录 | 需角色 | Controller#method | 请求体 | 响应 data |
|---|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/admin/grant` | 是 | `ADMIN` | `AuthController#grantAdmin` | `AuthGrantAdminRequestDTO` | `` |
| `POST` | `/api/v1/auth/admin/revoke` | 是 | `ADMIN` | `AuthController#revokeAdmin` | `AuthGrantAdminRequestDTO` | `` |
| `GET` | `/api/v1/auth/admins` | 是 | `ADMIN` | `AuthController#listAdmins` | `` | `AuthAdminListResponseDTO` |
| `POST` | `/api/v1/auth/login/password` | 否 | `` | `AuthController#passwordLogin` | `AuthPasswordLoginRequestDTO` | `AuthTokenResponseDTO` |
| `POST` | `/api/v1/auth/logout` | 是 | `` | `AuthController#logout` | `` | `` |
| `GET` | `/api/v1/auth/me` | 是 | `` | `AuthController#me` | `` | `AuthMeResponseDTO` |
| `POST` | `/api/v1/auth/password/change` | 是 | `` | `AuthController#changePassword` | `AuthChangePasswordRequestDTO` | `` |
| `POST` | `/api/v1/auth/register` | 否 | `` | `AuthController#register` | `AuthRegisterRequestDTO` | `AuthRegisterResponseDTO` |
| `GET` | `/api/v1/comment/hot` | 是 | `` | `CommentController#hot` | `` | `CommentHotResponseDTO` |
| `GET` | `/api/v1/comment/list` | 是 | `` | `CommentController#list` | `` | `CommentListResponseDTO` |
| `GET` | `/api/v1/comment/reply/list` | 是 | `` | `CommentController#replyList` | `` | `CommentReplyListResponseDTO` |
| `DELETE` | `/api/v1/comment/{commentId}` | 是 | `` | `CommentController#delete` | `` | `OperationResultDTO` |
| `PUT` | `/api/v1/content/draft` | 是 | `` | `ContentController#saveDraft` | `SaveDraftRequestDTO` | `SaveDraftResponseDTO` |
| `PATCH` | `/api/v1/content/draft/{draftId}` | 是 | `` | `ContentController#syncDraft` | `DraftSyncRequestDTO` | `DraftSyncResponseDTO` |
| `POST` | `/api/v1/content/publish` | 是 | `` | `ContentController#publish` | `PublishContentRequestDTO` | `PublishContentResponseDTO` |
| `GET` | `/api/v1/content/publish/attempt/{attemptId}` | 是 | `` | `ContentController#publishAttempt` | `` | `PublishAttemptResponseDTO` |
| `PATCH` | `/api/v1/content/schedule` | 是 | `` | `ContentController#updateSchedule` | `ScheduleUpdateRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/content/schedule` | 是 | `` | `ContentController#schedule` | `ScheduleContentRequestDTO` | `ScheduleContentResponseDTO` |
| `POST` | `/api/v1/content/schedule/cancel` | 是 | `` | `ContentController#cancelSchedule` | `ScheduleCancelRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/content/schedule/{taskId}` | 是 | `` | `ContentController#scheduleAudit` | `` | `ScheduleAuditResponseDTO` |
| `DELETE` | `/api/v1/content/{postId}` | 是 | `` | `ContentController#delete` | `DeleteContentRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/content/{postId}` | 是 | `` | `ContentController#detail` | `` | `ContentDetailResponseDTO` |
| `GET` | `/api/v1/content/{postId}/history` | 是 | `` | `ContentController#history` | `` | `ContentHistoryResponseDTO` |
| `POST` | `/api/v1/content/{postId}/rollback` | 是 | `` | `ContentController#rollback` | `ContentRollbackRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/feed/profile/{targetId}` | 是 | `` | `FeedController#profile` | `` | `FeedTimelineResponseDTO` |
| `GET` | `/api/v1/feed/timeline` | 是 | `` | `FeedController#timeline` | `` | `FeedTimelineResponseDTO` |
| `GET` | `/api/v1/health` | 否 | `` | `SystemHealthController#health` | `` | `SystemHealthResponseDTO` |
| `POST` | `/api/v1/interact/comment` | 是 | `` | `InteractionController#comment` | `CommentRequestDTO` | `CommentResponseDTO` |
| `POST` | `/api/v1/interact/comment/pin` | 是 | `` | `InteractionController#pinComment` | `PinCommentRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/interact/reaction` | 是 | `` | `InteractionController#react` | `ReactionRequestDTO` | `ReactionResponseDTO` |
| `GET` | `/api/v1/interact/reaction/state` | 是 | `` | `InteractionController#reactionState` | `` | `ReactionStateResponseDTO` |
| `POST` | `/api/v1/internal/user/upsert` | 是 | `` | `InternalUserController#upsert` | `UserInternalUpsertRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/media/upload/session` | 是 | `` | `ContentController#createUploadSession` | `UploadSessionRequestDTO` | `UploadSessionResponseDTO` |
| `GET` | `/api/v1/notification/list` | 是 | `` | `InteractionController#notifications` | `` | `NotificationListResponseDTO` |
| `POST` | `/api/v1/notification/read` | 是 | `` | `InteractionController#readNotification` | `NotificationReadRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/notification/read/all` | 是 | `` | `InteractionController#readAllNotifications` | `` | `OperationResultDTO` |
| `POST` | `/api/v1/relation/block` | 是 | `` | `RelationController#block` | `BlockRequestDTO` | `BlockResponseDTO` |
| `POST` | `/api/v1/relation/follow` | 是 | `` | `RelationController#follow` | `FollowRequestDTO` | `FollowResponseDTO` |
| `GET` | `/api/v1/relation/followers` | 是 | `` | `RelationController#followers` | `` | `RelationListResponseDTO` |
| `GET` | `/api/v1/relation/following` | 是 | `` | `RelationController#following` | `` | `RelationListResponseDTO` |
| `POST` | `/api/v1/relation/state/batch` | 是 | `` | `RelationController#stateBatch` | `RelationStateBatchRequestDTO` | `RelationStateBatchResponseDTO` |
| `POST` | `/api/v1/relation/unfollow` | 是 | `` | `RelationController#unfollow` | `FollowRequestDTO` | `FollowResponseDTO` |
| `POST` | `/api/v1/risk/admin/appeals/{appealId}/decision` | 是 | `ADMIN` | `RiskAdminController#decideAppeal` | `RiskAppealDecisionRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/risk/admin/cases` | 是 | `ADMIN` | `RiskAdminController#listCases` | `` | `RiskCaseListResponseDTO` |
| `POST` | `/api/v1/risk/admin/cases/{caseId}/assign` | 是 | `ADMIN` | `RiskAdminController#assignCase` | `RiskCaseAssignRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/risk/admin/cases/{caseId}/decision` | 是 | `ADMIN` | `RiskAdminController#decideCase` | `RiskCaseDecisionRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/risk/admin/decisions` | 是 | `ADMIN` | `RiskAdminController#listDecisions` | `` | `RiskDecisionLogListResponseDTO` |
| `POST` | `/api/v1/risk/admin/prompts/rollback` | 是 | `ADMIN` | `RiskAdminController#rollbackPromptVersion` | `RiskPromptVersionRollbackRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/risk/admin/prompts/versions` | 是 | `ADMIN` | `RiskAdminController#listPromptVersions` | `` | `RiskPromptVersionListResponseDTO` |
| `POST` | `/api/v1/risk/admin/prompts/versions` | 是 | `ADMIN` | `RiskAdminController#upsertPromptVersion` | `RiskPromptVersionUpsertRequestDTO` | `RiskPromptVersionUpsertResponseDTO` |
| `POST` | `/api/v1/risk/admin/prompts/versions/{version}/publish` | 是 | `ADMIN` | `RiskAdminController#publishPromptVersion` | `RiskPromptVersionPublishRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/risk/admin/punishments` | 是 | `ADMIN` | `RiskAdminController#listPunishments` | `` | `RiskPunishmentListResponseDTO` |
| `POST` | `/api/v1/risk/admin/punishments/apply` | 是 | `ADMIN` | `RiskAdminController#applyPunishment` | `RiskPunishmentApplyRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/risk/admin/punishments/revoke` | 是 | `ADMIN` | `RiskAdminController#revokePunishment` | `RiskPunishmentRevokeRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/risk/admin/rules/rollback` | 是 | `ADMIN` | `RiskAdminController#rollbackRuleVersion` | `RiskRuleVersionRollbackRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/risk/admin/rules/versions` | 是 | `ADMIN` | `RiskAdminController#listRuleVersions` | `` | `RiskRuleVersionListResponseDTO` |
| `POST` | `/api/v1/risk/admin/rules/versions` | 是 | `ADMIN` | `RiskAdminController#upsertRuleVersion` | `RiskRuleVersionUpsertRequestDTO` | `RiskRuleVersionUpsertResponseDTO` |
| `POST` | `/api/v1/risk/admin/rules/versions/{version}/publish` | 是 | `ADMIN` | `RiskAdminController#publishRuleVersion` | `RiskRuleVersionPublishRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/risk/appeals` | 是 | `` | `RiskController#appeal` | `RiskAppealRequestDTO` | `RiskAppealResponseDTO` |
| `POST` | `/api/v1/risk/decision` | 是 | `` | `RiskController#decision` | `RiskDecisionRequestDTO` | `RiskDecisionResponseDTO` |
| `POST` | `/api/v1/risk/scan/image` | 是 | `` | `RiskController#imageScan` | `ImageScanRequestDTO` | `ImageScanResponseDTO` |
| `POST` | `/api/v1/risk/scan/text` | 是 | `` | `RiskController#textScan` | `TextScanRequestDTO` | `TextScanResponseDTO` |
| `GET` | `/api/v1/risk/user/status` | 是 | `` | `RiskController#userStatus` | `` | `UserRiskStatusResponseDTO` |
| `GET` | `/api/v1/search` | 是 | `` | `SearchController#search` | `` | `SearchResponseDTO` |
| `GET` | `/api/v1/search/suggest` | 是 | `` | `SearchController#suggest` | `` | `SuggestResponseDTO` |
| `GET` | `/api/v1/user/me/privacy` | 是 | `` | `UserSettingController#myPrivacy` | `` | `UserPrivacyResponseDTO` |
| `POST` | `/api/v1/user/me/privacy` | 是 | `` | `UserSettingController#updateMyPrivacy` | `UserPrivacyUpdateRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/user/me/profile` | 是 | `` | `UserProfileController#myProfile` | `` | `UserProfileResponseDTO` |
| `POST` | `/api/v1/user/me/profile` | 是 | `` | `UserProfileController#updateMyProfile` | `UserProfileUpdateRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/user/profile` | 是 | `` | `UserProfileController#profile` | `` | `UserProfileResponseDTO` |
| `GET` | `/api/v1/user/profile/page` | 是 | `` | `UserProfilePageController#profilePage` | `` | `UserProfilePageResponseDTO` |
| `POST` | `/file/upload` | 否 | `` | `FileController#upload` | `` | `FileUploadResponseDTO` |
| `GET` | `/id/segment/get/{key}` | 否 | `` | `IdController#segment` | `` | `String` |
| `GET` | `/id/snowflake/get/{key}` | 否 | `` | `IdController#snowflake` | `` | `String` |
| `POST` | `/kv/comment/content/batchAdd` | 否 | `` | `CommentContentController#batchAdd` | `KvCommentContentBatchAddRequestDTO` | `Object` |
| `POST` | `/kv/comment/content/batchFind` | 否 | `` | `CommentContentController#batchFind` | `KvCommentContentBatchFindRequestDTO` | `List<KvCommentContentFoundDTO>` |
| `POST` | `/kv/comment/content/delete` | 否 | `` | `CommentContentController#delete` | `KvCommentContentDeleteRequestDTO` | `Object` |
| `POST` | `/kv/note/content/add` | 否 | `` | `NoteContentController#add` | `KvNoteContentAddRequestDTO` | `Object` |
| `POST` | `/kv/note/content/delete` | 否 | `` | `NoteContentController#delete` | `KvNoteContentDeleteRequestDTO` | `Object` |
| `POST` | `/kv/note/content/find` | 否 | `` | `NoteContentController#find` | `KvNoteContentFindRequestDTO` | `String` |

## 2) 接口详情（按模块分组）

### Feed

#### `GET /api/v1/feed/profile/{targetId}`

- 鉴权：需要登录
- 路径参数：
  - `targetId`: `Long`
- Query 参数（来自 DTO 字段）：
  - `targetId`: `Long`
  - `visitorId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<FeedItemDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`

#### `GET /api/v1/feed/timeline`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
  - `feedType`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<FeedItemDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`

### ID 服务

#### `GET /id/segment/get/{key}`

- 鉴权：匿名可调用
- 路径参数：
  - `key`: `String`
- 响应：非 `Response` 包装（直接返回原始内容）
  - 返回类型：`String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/id/IdController.java`

#### `GET /id/snowflake/get/{key}`

- 鉴权：匿名可调用
- 路径参数：
  - `key`: `String`
- 响应：非 `Response` 包装（直接返回原始内容）
  - 返回类型：`String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/id/IdController.java`

### KV（内部存储）

#### `POST /kv/comment/content/batchAdd`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvCommentContentBatchAddRequestDTO`
  - `comments`: `List<KvCommentContentDTO>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 类型：`Object`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/CommentContentController.java`

#### `POST /kv/comment/content/batchFind`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvCommentContentBatchFindRequestDTO`
  - `noteId`: `Long`
  - `commentContentKeys`: `List<KvCommentContentKeyDTO>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data`：数组 `List<KvCommentContentFoundDTO>`
  - 每一项字段：
    - `contentId`: `String`
    - `content`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/CommentContentController.java`

#### `POST /kv/comment/content/delete`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvCommentContentDeleteRequestDTO`
  - `noteId`: `Long`
  - `yearMonth`: `String`
  - `contentId`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 类型：`Object`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/CommentContentController.java`

#### `POST /kv/note/content/add`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvNoteContentAddRequestDTO`
  - `uuid`: `String`
  - `content`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 类型：`Object`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/NoteContentController.java`

#### `POST /kv/note/content/delete`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvNoteContentDeleteRequestDTO`
  - `uuid`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 类型：`Object`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/NoteContentController.java`

#### `POST /kv/note/content/find`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`KvNoteContentFindRequestDTO`
  - `uuid`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 类型：`String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/NoteContentController.java`

### 互动 / 通知 / 钱包

#### `POST /api/v1/interact/comment`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`CommentRequestDTO`
  - `postId`: `Long`
  - `parentId`: `Long`
  - `content`: `String`
  - `commentId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `commentId`: `Long`
    - `createTime`: `Long`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/interact/comment/pin`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`PinCommentRequestDTO`
  - `commentId`: `Long`
  - `postId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/interact/reaction`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ReactionRequestDTO`
  - `requestId`: `String`
  - `targetId`: `Long`
  - `targetType`: `String`
  - `type`: `String`
  - `action`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `requestId`: `String`
    - `currentCount`: `Long`
    - `success`: `boolean`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `GET /api/v1/interact/reaction/state`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `targetId`: `Long`
  - `targetType`: `String`
  - `type`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `state`: `boolean`
    - `currentCount`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `GET /api/v1/notification/list`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `cursor`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `notifications`: `List<NotificationDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/notification/read`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`NotificationReadRequestDTO`
  - `notificationId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/notification/read/all`

- 鉴权：需要登录
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

### 健康 Health

#### `GET /api/v1/health`

- 鉴权：匿名可调用
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/SystemHealthController.java`

### 关系 Relation

#### `POST /api/v1/relation/block`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`BlockRequestDTO`
  - `sourceId`: `Long`
  - `targetId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

#### `POST /api/v1/relation/follow`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`FollowRequestDTO`
  - `sourceId`: `Long`
  - `targetId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

#### `GET /api/v1/relation/followers`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<RelationUserDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

#### `GET /api/v1/relation/following`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<RelationUserDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

#### `POST /api/v1/relation/state/batch`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`RelationStateBatchRequestDTO`
  - `targetUserIds`: `List<Long>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `followingUserIds`: `List<Long>`
    - `blockedUserIds`: `List<Long>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

#### `POST /api/v1/relation/unfollow`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`FollowRequestDTO`
  - `sourceId`: `Long`
  - `targetId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

### 内容 Content & Media

#### `PUT /api/v1/content/draft`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`SaveDraftRequestDTO`
  - `draftId`: `Long`
  - `userId`: `Long`
  - `title`: `String`
  - `contentText`: `String`
  - `mediaIds`: `List<String>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `draftId`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `PATCH /api/v1/content/draft/{draftId}`

- 鉴权：需要登录
- 路径参数：
  - `draftId`: `Long`
- 请求体（JSON）：
  - 类型：`DraftSyncRequestDTO`
  - `draftId`: `Long`
  - `title`: `String`
  - `diffContent`: `String`
  - `clientVersion`: `Long`
  - `deviceId`: `String`
  - `mediaIds`: `List<String>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `serverVersion`: `String`
    - `syncTime`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `POST /api/v1/content/publish`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`PublishContentRequestDTO`
  - `postId`: `Long`
  - `userId`: `Long`
  - `title`: `String`
  - `text`: `String`
  - `mediaInfo`: `String`
  - `location`: `String`
  - `visibility`: `String`
  - `postTypes`: `List<String>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `postId`: `Long`
    - `attemptId`: `Long`
    - `versionNum`: `Integer`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `GET /api/v1/content/publish/attempt/{attemptId}`

- 鉴权：需要登录
- 路径参数：
  - `attemptId`: `Long`
- Query 参数：
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `attemptId`: `Long`
    - `postId`: `Long`
    - `userId`: `Long`
    - `idempotentToken`: `String`
    - `transcodeJobId`: `String`
    - `attemptStatus`: `Integer`
    - `riskStatus`: `Integer`
    - `transcodeStatus`: `Integer`
    - `publishedVersionNum`: `Integer`
    - `errorCode`: `String`
    - `errorMessage`: `String`
    - `createTime`: `Long`
    - `updateTime`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `PATCH /api/v1/content/schedule`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ScheduleUpdateRequestDTO`
  - `taskId`: `Long`
  - `userId`: `Long`
  - `publishTime`: `Long`
  - `contentData`: `String`
  - `reason`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `POST /api/v1/content/schedule`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ScheduleContentRequestDTO`
  - `postId`: `Long`
  - `publishTime`: `Long`
  - `timezone`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `taskId`: `Long`
    - `postId`: `Long`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `POST /api/v1/content/schedule/cancel`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ScheduleCancelRequestDTO`
  - `taskId`: `Long`
  - `userId`: `Long`
  - `reason`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `GET /api/v1/content/schedule/{taskId}`

- 鉴权：需要登录
- 路径参数：
  - `taskId`: `Long`
- Query 参数：
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `taskId`: `Long`
    - `userId`: `Long`
    - `scheduleTime`: `Long`
    - `status`: `Integer`
    - `retryCount`: `Integer`
    - `isCanceled`: `Integer`
    - `lastError`: `String`
    - `alarmSent`: `Integer`
    - `contentData`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `DELETE /api/v1/content/{postId}`

- 鉴权：需要登录
- 路径参数：
  - `postId`: `Long`
- 请求体（JSON）：
  - 类型：`DeleteContentRequestDTO`
  - `userId`: `Long`
  - `postId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `GET /api/v1/content/{postId}`

- 鉴权：需要登录
- 路径参数：
  - `postId`: `Long`
- Query 参数：
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `postId`: `Long`
    - `authorId`: `Long`
    - `authorNickname`: `String`
    - `authorAvatarUrl`: `String`
    - `title`: `String`
    - `content`: `String`
    - `summary`: `String`
    - `summaryStatus`: `Integer`
    - `mediaType`: `Integer`
    - `mediaInfo`: `String`
    - `locationInfo`: `String`
    - `status`: `Integer`
    - `visibility`: `Integer`
    - `versionNum`: `Integer`
    - `edited`: `Boolean`
    - `createTime`: `Long`
    - `likeCount`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `GET /api/v1/content/{postId}/history`

- 鉴权：需要登录
- 路径参数：
  - `postId`: `Long`
- Query 参数：
  - `userId`: `Long`
  - `limit`: `Integer`
  - `offset`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `versions`: `List<ContentVersionDTO>`
    - `nextCursor`: `Integer`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `POST /api/v1/content/{postId}/rollback`

- 鉴权：需要登录
- 路径参数：
  - `postId`: `Long`
- 请求体（JSON）：
  - 类型：`ContentRollbackRequestDTO`
  - `postId`: `Long`
  - `userId`: `Long`
  - `targetVersionId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

#### `POST /api/v1/media/upload/session`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`UploadSessionRequestDTO`
  - `fileType`: `String`
  - `fileSize`: `Long`
  - `crc32`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `uploadUrl`: `String`
    - `token`: `String`
    - `sessionId`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

### 内部 Internal

#### `POST /api/v1/internal/user/upsert`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`UserInternalUpsertRequestDTO`
  - `userId`: `Long`
  - `username`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`
  - `needApproval`: `Boolean`
  - `status`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/InternalUserController.java`

### 搜索 Search

#### `GET /api/v1/search`

- 鉴权：需要登录
- Query 参数：
  - `q`: `String`
  - `size`: `Integer`
  - `tags`: `String`
  - `after`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<SearchItemDTO>`
    - `nextAfter`: `String`
    - `hasMore`: `boolean`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`

#### `GET /api/v1/search/suggest`

- 鉴权：需要登录
- Query 参数：
  - `prefix`: `String`
  - `size`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<String>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`

### 文件上传（兼容）

#### `POST /file/upload`

- 鉴权：匿名可调用
- 表单参数（multipart/form-data）：
  - `file`: `MultipartFile`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `url`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/file/FileController.java`

### 用户 User

#### `GET /api/v1/user/me/privacy`

- 鉴权：需要登录
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `needApproval`: `Boolean`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserSettingController.java`

#### `POST /api/v1/user/me/privacy`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`UserPrivacyUpdateRequestDTO`
  - `needApproval`: `Boolean`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserSettingController.java`

#### `GET /api/v1/user/me/profile`

- 鉴权：需要登录
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
    - `username`: `String`
    - `nickname`: `String`
    - `avatarUrl`: `String`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfileController.java`

#### `POST /api/v1/user/me/profile`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`UserProfileUpdateRequestDTO`
  - `nickname`: `String`
  - `avatarUrl`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfileController.java`

#### `GET /api/v1/user/profile`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `targetUserId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
    - `username`: `String`
    - `nickname`: `String`
    - `avatarUrl`: `String`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfileController.java`

#### `GET /api/v1/user/profile/page`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `targetUserId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `profile`: `UserProfileResponseDTO`
    - `relation`: `UserRelationStatsDTO`
    - `risk`: `UserRiskStatusResponseDTO`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfilePageController.java`

### 社群 Group

### 认证 Auth

#### `POST /api/v1/auth/admin/grant`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`AuthGrantAdminRequestDTO`
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data`：空（`null`）
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/admin/revoke`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`AuthGrantAdminRequestDTO`
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data`：空（`null`）
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `GET /api/v1/auth/admins`

- 鉴权：需要登录；需要角色 `ADMIN`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userIds`: `List<Long>`
    - `admins`: `List<AuthAdminDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/login/password`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`AuthPasswordLoginRequestDTO`
  - `phone`: `String`
  - `password`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
    - `tokenName`: `String`
    - `tokenPrefix`: `String`
    - `token`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/logout`

- 鉴权：需要登录
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data`：空（`null`）
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `GET /api/v1/auth/me`

- 鉴权：需要登录
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
    - `phone`: `String`
    - `status`: `String`
    - `nickname`: `String`
    - `avatarUrl`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/password/change`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`AuthChangePasswordRequestDTO`
  - `oldPassword`: `String`
  - `newPassword`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data`：空（`null`）
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/register`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`AuthRegisterRequestDTO`
  - `phone`: `String`
  - `password`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

### 评论 Comment

#### `GET /api/v1/comment/hot`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `postId`: `Long`
  - `limit`: `Integer`
  - `preloadReplyLimit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `pinned`: `RootCommentViewDTO`
    - `items`: `List<RootCommentViewDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java`

#### `GET /api/v1/comment/list`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `postId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
  - `preloadReplyLimit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `pinned`: `RootCommentViewDTO`
    - `items`: `List<RootCommentViewDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java`

#### `GET /api/v1/comment/reply/list`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `rootId`: `Long`
  - `cursor`: `String`
  - `limit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<CommentViewDTO>`
    - `nextCursor`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java`

#### `DELETE /api/v1/comment/{commentId}`

- 鉴权：需要登录
- 路径参数：
  - `commentId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java`

### 风控 Risk

#### `POST /api/v1/risk/appeals`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`RiskAppealRequestDTO`
  - `decisionId`: `Long`
  - `punishId`: `Long`
  - `content`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `appealId`: `Long`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`

#### `POST /api/v1/risk/decision`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`RiskDecisionRequestDTO`
  - `eventId`: `String`
  - `actionType`: `String`
  - `scenario`: `String`
  - `contentText`: `String`
  - `mediaUrls`: `List<String>`
  - `targetId`: `String`
  - `ext`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `decisionId`: `Long`
    - `result`: `String`
    - `reasonCode`: `String`
    - `actions`: `List<RiskActionDTO>`
    - `signals`: `List<RiskSignalDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`

#### `POST /api/v1/risk/scan/image`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ImageScanRequestDTO`
  - `imageUrl`: `String`
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `taskId`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`

#### `POST /api/v1/risk/scan/text`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`TextScanRequestDTO`
  - `content`: `String`
  - `userId`: `Long`
  - `scenario`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `result`: `String`
    - `tags`: `List<String>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`

#### `GET /api/v1/risk/user/status`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `status`: `String`
    - `capabilities`: `List<String>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`

### 风控后台 Risk Admin

#### `POST /api/v1/risk/admin/appeals/{appealId}/decision`

- 鉴权：需要登录；需要角色 `ADMIN`
- 路径参数：
  - `appealId`: `Long`
- 请求体（JSON）：
  - 类型：`RiskAppealDecisionRequestDTO`
  - `result`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `GET /api/v1/risk/admin/cases`

- 鉴权：需要登录；需要角色 `ADMIN`
- Query 参数（来自 DTO 字段）：
  - `status`: `String`
  - `queue`: `String`
  - `beginTime`: `Long`
  - `endTime`: `Long`
  - `limit`: `Integer`
  - `offset`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `cases`: `List<RiskCaseDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/cases/{caseId}/assign`

- 鉴权：需要登录；需要角色 `ADMIN`
- 路径参数：
  - `caseId`: `Long`
- 请求体（JSON）：
  - 类型：`RiskCaseAssignRequestDTO`
  - `assignee`: `Long`
  - `expectedStatus`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/cases/{caseId}/decision`

- 鉴权：需要登录；需要角色 `ADMIN`
- 路径参数：
  - `caseId`: `Long`
- 请求体（JSON）：
  - 类型：`RiskCaseDecisionRequestDTO`
  - `result`: `String`
  - `reasonCode`: `String`
  - `evidenceJson`: `String`
  - `expectedStatus`: `String`
  - `punishType`: `String`
  - `punishDurationSeconds`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `GET /api/v1/risk/admin/decisions`

- 鉴权：需要登录；需要角色 `ADMIN`
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `actionType`: `String`
  - `scenario`: `String`
  - `result`: `String`
  - `beginTime`: `Long`
  - `endTime`: `Long`
  - `limit`: `Integer`
  - `offset`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `decisions`: `List<RiskDecisionLogDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/prompts/rollback`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskPromptVersionRollbackRequestDTO`
  - `toVersion`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `GET /api/v1/risk/admin/prompts/versions`

- 鉴权：需要登录；需要角色 `ADMIN`
- Query 参数（来自 DTO 字段）：
  - `contentType`: `String`
  - `includePromptText`: `Boolean`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `activeTextVersion`: `Long`
    - `activeImageVersion`: `Long`
    - `versions`: `List<RiskPromptVersionDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/prompts/versions`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskPromptVersionUpsertRequestDTO`
  - `version`: `Long`
  - `contentType`: `String`
  - `promptText`: `String`
  - `model`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `version`: `Long`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/prompts/versions/{version}/publish`

- 鉴权：需要登录；需要角色 `ADMIN`
- 路径参数：
  - `version`: `Long`
- 请求体（JSON）：
  - 类型：`RiskPromptVersionPublishRequestDTO`
  - `reserved`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `GET /api/v1/risk/admin/punishments`

- 鉴权：需要登录；需要角色 `ADMIN`
- Query 参数（来自 DTO 字段）：
  - `userId`: `Long`
  - `type`: `String`
  - `beginTime`: `Long`
  - `endTime`: `Long`
  - `limit`: `Integer`
  - `offset`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `punishments`: `List<RiskPunishmentDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/punishments/apply`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskPunishmentApplyRequestDTO`
  - `userId`: `Long`
  - `type`: `String`
  - `decisionId`: `Long`
  - `reasonCode`: `String`
  - `startTime`: `Long`
  - `endTime`: `Long`
  - `durationSeconds`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/punishments/revoke`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskPunishmentRevokeRequestDTO`
  - `punishId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/rules/rollback`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskRuleVersionRollbackRequestDTO`
  - `toVersion`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `GET /api/v1/risk/admin/rules/versions`

- 鉴权：需要登录；需要角色 `ADMIN`
- Query 参数（来自 DTO 字段）：
  - `includeRulesJson`: `Boolean`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `activeVersion`: `Long`
    - `versions`: `List<RiskRuleVersionDTO>`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/rules/versions`

- 鉴权：需要登录；需要角色 `ADMIN`
- 请求体（JSON）：
  - 类型：`RiskRuleVersionUpsertRequestDTO`
  - `version`: `Long`
  - `rulesJson`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `version`: `Long`
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

#### `POST /api/v1/risk/admin/rules/versions/{version}/publish`

- 鉴权：需要登录；需要角色 `ADMIN`
- 路径参数：
  - `version`: `Long`
- 请求体（JSON）：
  - 类型：`RiskRuleVersionPublishRequestDTO`
  - `shadow`: `Boolean`
  - `canaryPercent`: `Integer`
  - `canarySalt`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

## 3) DTO 字典（前端不看代码也能对接）

<!-- DTO-DICT:START -->
> 说明：下面只保证“字段名 + 类型”准确；示例 JSON 只是为了让你看懂形状，不代表真实业务值。

### DTO 索引
- `AuthAdminDTO`
- `AuthAdminListResponseDTO`
- `AuthChangePasswordRequestDTO`
- `AuthGrantAdminRequestDTO`
- `AuthMeResponseDTO`
- `AuthPasswordLoginRequestDTO`
- `AuthRegisterRequestDTO`
- `AuthRegisterResponseDTO`
- `AuthTokenResponseDTO`
- `BlockRequestDTO`
- `BlockResponseDTO`
- `CommentHotResponseDTO`
- `CommentListResponseDTO`
- `CommentReplyListResponseDTO`
- `CommentRequestDTO`
- `CommentResponseDTO`
- `CommentViewDTO`
- `ContentDetailResponseDTO`
- `ContentHistoryResponseDTO`
- `DraftSyncResponseDTO`
- `FeedItemDTO`
- `FeedTimelineResponseDTO`
- `FileUploadResponseDTO`
- `FollowRequestDTO`
- `FollowResponseDTO`
- `ImageScanRequestDTO`
- `ImageScanResponseDTO`
- `KvCommentContentBatchAddRequestDTO`
- `KvCommentContentBatchFindRequestDTO`
- `KvCommentContentDTO`
- `KvCommentContentDeleteRequestDTO`
- `KvCommentContentFoundDTO`
- `KvCommentContentKeyDTO`
- `KvNoteContentAddRequestDTO`
- `KvNoteContentDeleteRequestDTO`
- `KvNoteContentFindRequestDTO`
- `NotificationDTO`
- `NotificationListResponseDTO`
- `NotificationReadRequestDTO`
- `OperationResultDTO`
- `PinCommentRequestDTO`
- `PublishAttemptResponseDTO`
- `PublishContentRequestDTO`
- `PublishContentResponseDTO`
- `ReactionRequestDTO`
- `ReactionResponseDTO`
- `ReactionStateResponseDTO`
- `RelationListResponseDTO`
- `RelationStateBatchRequestDTO`
- `RelationStateBatchResponseDTO`
- `RelationUserDTO`
- `RiskActionDTO`
- `RiskAppealRequestDTO`
- `RiskAppealResponseDTO`
- `RiskCaseDTO`
- `RiskCaseListResponseDTO`
- `RiskDecisionLogDTO`
- `RiskDecisionLogListResponseDTO`
- `RiskDecisionRequestDTO`
- `RiskDecisionResponseDTO`
- `RiskPromptVersionDTO`
- `RiskPromptVersionListResponseDTO`
- `RiskPromptVersionRollbackRequestDTO`
- `RiskPromptVersionUpsertRequestDTO`
- `RiskPromptVersionUpsertResponseDTO`
- `RiskPunishmentApplyRequestDTO`
- `RiskPunishmentDTO`
- `RiskPunishmentListResponseDTO`
- `RiskPunishmentRevokeRequestDTO`
- `RiskRuleVersionDTO`
- `RiskRuleVersionListResponseDTO`
- `RiskRuleVersionRollbackRequestDTO`
- `RiskRuleVersionUpsertRequestDTO`
- `RiskRuleVersionUpsertResponseDTO`
- `RiskSignalDTO`
- `RootCommentViewDTO`
- `SaveDraftRequestDTO`
- `SaveDraftResponseDTO`
- `ScheduleAuditResponseDTO`
- `ScheduleCancelRequestDTO`
- `ScheduleContentRequestDTO`
- `ScheduleContentResponseDTO`
- `ScheduleUpdateRequestDTO`
- `SearchItemDTO`
- `SearchResponseDTO`
- `SuggestResponseDTO`
- `SystemHealthResponseDTO`
- `TextScanRequestDTO`
- `TextScanResponseDTO`
- `UploadSessionRequestDTO`
- `UploadSessionResponseDTO`
- `UserInternalUpsertRequestDTO`
- `UserPrivacyResponseDTO`
- `UserPrivacyUpdateRequestDTO`
- `UserProfilePageResponseDTO`
- `UserProfileResponseDTO`
- `UserProfileUpdateRequestDTO`
- `UserRelationStatsDTO`
- `UserRiskStatusResponseDTO`

### `AuthAdminDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthAdminDTO.java`
- 字段：
  - `userId`: `Long`
  - `phone`: `String`
  - `status`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`

示例 JSON：
```json
{
  "userId": 0,
  "phone": "string",
  "status": "string",
  "nickname": "string",
  "avatarUrl": "string"
}
```

### `AuthAdminListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthAdminListResponseDTO.java`
- 字段：
  - `userIds`: `List<Long>`
  - `admins`: `List<AuthAdminDTO>`

示例 JSON：
```json
{
  "userIds": [
    0
  ],
  "admins": [
    {
      "userId": 0,
      "phone": "string",
      "status": "string",
      "nickname": "string",
      "avatarUrl": "string"
    }
  ]
}
```

### `AuthChangePasswordRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthChangePasswordRequestDTO.java`
- 字段：
  - `oldPassword`: `String`
  - `newPassword`: `String`

示例 JSON：
```json
{
  "oldPassword": "string",
  "newPassword": "string"
}
```

### `AuthGrantAdminRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthGrantAdminRequestDTO.java`
- 字段：
  - `userId`: `Long`

示例 JSON：
```json
{
  "userId": 0
}
```

### `AuthMeResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthMeResponseDTO.java`
- 字段：
  - `userId`: `Long`
  - `phone`: `String`
  - `status`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`

示例 JSON：
```json
{
  "userId": 0,
  "phone": "string",
  "status": "string",
  "nickname": "string",
  "avatarUrl": "string"
}
```

### `AuthPasswordLoginRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthPasswordLoginRequestDTO.java`
- 字段：
  - `phone`: `String`
  - `password`: `String`

示例 JSON：
```json
{
  "phone": "string",
  "password": "string"
}
```

### `AuthRegisterRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthRegisterRequestDTO.java`
- 字段：
  - `phone`: `String`
  - `password`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`

示例 JSON：
```json
{
  "phone": "string",
  "password": "string",
  "nickname": "string",
  "avatarUrl": "string"
}
```

### `AuthRegisterResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthRegisterResponseDTO.java`
- 字段：
  - `userId`: `Long`

示例 JSON：
```json
{
  "userId": 0
}
```

### `AuthTokenResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/auth/dto/AuthTokenResponseDTO.java`
- 字段：
  - `userId`: `Long`
  - `tokenName`: `String`
  - `tokenPrefix`: `String`
  - `token`: `String`

示例 JSON：
```json
{
  "userId": 0,
  "tokenName": "string",
  "tokenPrefix": "string",
  "token": "string"
}
```

### `BlockRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/BlockRequestDTO.java`
- 字段：
  - `sourceId`: `Long`
  - `targetId`: `Long`

示例 JSON：
```json
{
  "sourceId": 0,
  "targetId": 0
}
```

### `BlockResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/BlockResponseDTO.java`
- 字段：
  - `success`: `boolean`

示例 JSON：
```json
{
  "success": false
}
```

### `CommentHotResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentHotResponseDTO.java`
- 字段：
  - `pinned`: `RootCommentViewDTO`
  - `items`: `List<RootCommentViewDTO>`

示例 JSON：
```json
{
  "pinned": {
    "root": {
      "commentId": 0,
      "postId": 0,
      "userId": 0,
      "nickname": "string",
      "avatarUrl": "string",
      "rootId": 0,
      "parentId": 0,
      "replyToId": 0,
      "content": "string",
      "status": 0,
      "likeCount": 0,
      "replyCount": 0,
      "createTime": 0
    },
    "repliesPreview": [
      {}
    ]
  },
  "items": [
    {
      "root": {},
      "repliesPreview": [
        {}
      ]
    }
  ]
}
```

### `CommentListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentListResponseDTO.java`
- 字段：
  - `pinned`: `RootCommentViewDTO`
  - `items`: `List<RootCommentViewDTO>`
  - `nextCursor`: `String`

示例 JSON：
```json
{
  "pinned": {
    "root": {
      "commentId": 0,
      "postId": 0,
      "userId": 0,
      "nickname": "string",
      "avatarUrl": "string",
      "rootId": 0,
      "parentId": 0,
      "replyToId": 0,
      "content": "string",
      "status": 0,
      "likeCount": 0,
      "replyCount": 0,
      "createTime": 0
    },
    "repliesPreview": [
      {}
    ]
  },
  "items": [
    {
      "root": {},
      "repliesPreview": [
        {}
      ]
    }
  ],
  "nextCursor": "string"
}
```

### `CommentReplyListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentReplyListResponseDTO.java`
- 字段：
  - `items`: `List<CommentViewDTO>`
  - `nextCursor`: `String`

示例 JSON：
```json
{
  "items": [
    {
      "commentId": 0,
      "postId": 0,
      "userId": 0,
      "nickname": "string",
      "avatarUrl": "string",
      "rootId": 0,
      "parentId": 0,
      "replyToId": 0,
      "content": "string",
      "status": 0,
      "likeCount": 0,
      "replyCount": 0,
      "createTime": 0
    }
  ],
  "nextCursor": "string"
}
```

### `CommentRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentRequestDTO.java`
- 字段：
  - `postId`: `Long`
  - `parentId`: `Long`
  - `content`: `String`
  - `commentId`: `Long`

示例 JSON：
```json
{
  "postId": 0,
  "parentId": 0,
  "content": "string",
  "commentId": 0
}
```

### `CommentResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentResponseDTO.java`
- 字段：
  - `commentId`: `Long`
  - `createTime`: `Long`
  - `status`: `String`

示例 JSON：
```json
{
  "commentId": 0,
  "createTime": 0,
  "status": "string"
}
```

### `CommentViewDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentViewDTO.java`
- 字段：
  - `commentId`: `Long`
  - `postId`: `Long`
  - `userId`: `Long`
  - `nickname`: `String`
  - `avatarUrl`: `String`
  - `rootId`: `Long`
  - `parentId`: `Long`
  - `replyToId`: `Long`
  - `content`: `String`
  - `status`: `Integer`
  - `likeCount`: `Long`
  - `replyCount`: `Long`
  - `createTime`: `Long`

示例 JSON：
```json
{
  "commentId": 0,
  "postId": 0,
  "userId": 0,
  "nickname": "string",
  "avatarUrl": "string",
  "rootId": 0,
  "parentId": 0,
  "replyToId": 0,
  "content": "string",
  "status": 0,
  "likeCount": 0,
  "replyCount": 0,
  "createTime": 0
}
```

### `ContentDetailResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentDetailResponseDTO.java`
- 字段：
  - `postId`: `Long`
  - `authorId`: `Long`
  - `authorNickname`: `String`
  - `authorAvatarUrl`: `String`
  - `title`: `String`
  - `content`: `String`
  - `summary`: `String`
  - `summaryStatus`: `Integer`
  - `mediaType`: `Integer`
  - `mediaInfo`: `String`
  - `locationInfo`: `String`
  - `status`: `Integer`
  - `visibility`: `Integer`
  - `versionNum`: `Integer`
  - `edited`: `Boolean`
  - `createTime`: `Long`
  - `likeCount`: `Long`

示例 JSON：
```json
{
  "postId": 0,
  "authorId": 0,
  "authorNickname": "string",
  "authorAvatarUrl": "string",
  "title": "string",
  "content": "string",
  "summary": "string",
  "summaryStatus": 0,
  "mediaType": 0,
  "mediaInfo": "string",
  "locationInfo": "string",
  "status": 0,
  "visibility": 0,
  "versionNum": 0,
  "edited": false,
  "createTime": 0,
  "likeCount": 0
}
```

### `ContentHistoryResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentHistoryResponseDTO.java`
- 字段：
  - `versions`: `List<ContentVersionDTO>`
  - `nextCursor`: `Integer`
  - `versionId`: `Long`
  - `title`: `String`
  - `content`: `String`
  - `time`: `Long`

示例 JSON：
```json
{
  "versions": [
    {}
  ],
  "nextCursor": 0,
  "versionId": 0,
  "title": "string",
  "content": "string",
  "time": 0
}
```

### `DraftSyncResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/DraftSyncResponseDTO.java`
- 字段：
  - `serverVersion`: `String`
  - `syncTime`: `Long`

示例 JSON：
```json
{
  "serverVersion": "string",
  "syncTime": 0
}
```

### `FeedItemDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedItemDTO.java`
- 字段：
  - `postId`: `Long`
  - `authorId`: `Long`
  - `authorNickname`: `String`
  - `authorAvatar`: `String`
  - `text`: `String`
  - `summary`: `String`
  - `mediaType`: `Integer`
  - `mediaInfo`: `String`
  - `publishTime`: `Long`
  - `source`: `String`
  - `likeCount`: `Long`
  - `liked`: `Boolean`
  - `followed`: `Boolean`
  - `seen`: `Boolean`

示例 JSON：
```json
{
  "postId": 0,
  "authorId": 0,
  "authorNickname": "string",
  "authorAvatar": "string",
  "text": "string",
  "summary": "string",
  "mediaType": 0,
  "mediaInfo": "string",
  "publishTime": 0,
  "source": "string",
  "likeCount": 0,
  "liked": false,
  "followed": false,
  "seen": false
}
```

### `FeedTimelineResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedTimelineResponseDTO.java`
- 字段：
  - `items`: `List<FeedItemDTO>`
  - `nextCursor`: `String`

示例 JSON：
```json
{
  "items": [
    {
      "postId": 0,
      "authorId": 0,
      "authorNickname": "string",
      "authorAvatar": "string",
      "text": "string",
      "summary": "string",
      "mediaType": 0,
      "mediaInfo": "string",
      "publishTime": 0,
      "source": "string",
      "likeCount": 0,
      "liked": false,
      "followed": false,
      "seen": false
    }
  ],
  "nextCursor": "string"
}
```

### `FileUploadResponseDTO`

- 来源：`nexus-trigger/src/main/java/cn/nexus/trigger/http/file/dto/FileUploadResponseDTO.java`
- 字段：
  - `url`: `String`

示例 JSON：
```json
{
  "url": "string"
}
```

### `FollowRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/FollowRequestDTO.java`
- 字段：
  - `sourceId`: `Long`
  - `targetId`: `Long`

示例 JSON：
```json
{
  "sourceId": 0,
  "targetId": 0
}
```

### `FollowResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/FollowResponseDTO.java`
- 字段：
  - `status`: `String`

示例 JSON：
```json
{
  "status": "string"
}
```

### `ImageScanRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/ImageScanRequestDTO.java`
- 字段：
  - `imageUrl`: `String`
  - `userId`: `Long`

示例 JSON：
```json
{
  "imageUrl": "string",
  "userId": 0
}
```

### `ImageScanResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/ImageScanResponseDTO.java`
- 字段：
  - `taskId`: `String`

示例 JSON：
```json
{
  "taskId": "string"
}
```

### `KvCommentContentBatchAddRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentBatchAddRequestDTO.java`
- 字段：
  - `comments`: `List<KvCommentContentDTO>`

示例 JSON：
```json
{
  "comments": [
    {
      "noteId": 0,
      "yearMonth": "string",
      "contentId": "string",
      "content": "string"
    }
  ]
}
```

### `KvCommentContentBatchFindRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentBatchFindRequestDTO.java`
- 字段：
  - `noteId`: `Long`
  - `commentContentKeys`: `List<KvCommentContentKeyDTO>`

示例 JSON：
```json
{
  "noteId": 0,
  "commentContentKeys": [
    {
      "yearMonth": "string",
      "contentId": "string"
    }
  ]
}
```

### `KvCommentContentDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentDTO.java`
- 字段：
  - `noteId`: `Long`
  - `yearMonth`: `String`
  - `contentId`: `String`
  - `content`: `String`

示例 JSON：
```json
{
  "noteId": 0,
  "yearMonth": "string",
  "contentId": "string",
  "content": "string"
}
```

### `KvCommentContentDeleteRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentDeleteRequestDTO.java`
- 字段：
  - `noteId`: `Long`
  - `yearMonth`: `String`
  - `contentId`: `String`

示例 JSON：
```json
{
  "noteId": 0,
  "yearMonth": "string",
  "contentId": "string"
}
```

### `KvCommentContentFoundDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentFoundDTO.java`
- 字段：
  - `contentId`: `String`
  - `content`: `String`

示例 JSON：
```json
{
  "contentId": "string",
  "content": "string"
}
```

### `KvCommentContentKeyDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/comment/dto/KvCommentContentKeyDTO.java`
- 字段：
  - `yearMonth`: `String`
  - `contentId`: `String`

示例 JSON：
```json
{
  "yearMonth": "string",
  "contentId": "string"
}
```

### `KvNoteContentAddRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/note/dto/KvNoteContentAddRequestDTO.java`
- 字段：
  - `uuid`: `String`
  - `content`: `String`

示例 JSON：
```json
{
  "uuid": "string",
  "content": "string"
}
```

### `KvNoteContentDeleteRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/note/dto/KvNoteContentDeleteRequestDTO.java`
- 字段：
  - `uuid`: `String`

示例 JSON：
```json
{
  "uuid": "string"
}
```

### `KvNoteContentFindRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/kv/note/dto/KvNoteContentFindRequestDTO.java`
- 字段：
  - `uuid`: `String`

示例 JSON：
```json
{
  "uuid": "string"
}
```

### `NotificationDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/NotificationDTO.java`
- 字段：
  - `title`: `String`
  - `content`: `String`
  - `createTime`: `Long`
  - `notificationId`: `Long`
  - `bizType`: `String`
  - `targetType`: `String`
  - `targetId`: `Long`
  - `postId`: `Long`
  - `rootCommentId`: `Long`
  - `lastCommentId`: `Long`
  - `lastActorUserId`: `Long`
  - `unreadCount`: `Long`

示例 JSON：
```json
{
  "title": "string",
  "content": "string",
  "createTime": 0,
  "notificationId": 0,
  "bizType": "string",
  "targetType": "string",
  "targetId": 0,
  "postId": 0,
  "rootCommentId": 0,
  "lastCommentId": 0,
  "lastActorUserId": 0,
  "unreadCount": 0
}
```

### `NotificationListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/NotificationListResponseDTO.java`
- 字段：
  - `notifications`: `List<NotificationDTO>`
  - `nextCursor`: `String`

示例 JSON：
```json
{
  "notifications": [
    {
      "title": "string",
      "content": "string",
      "createTime": 0,
      "notificationId": 0,
      "bizType": "string",
      "targetType": "string",
      "targetId": 0,
      "postId": 0,
      "rootCommentId": 0,
      "lastCommentId": 0,
      "lastActorUserId": 0,
      "unreadCount": 0
    }
  ],
  "nextCursor": "string"
}
```

### `NotificationReadRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/NotificationReadRequestDTO.java`
- 字段：
  - `notificationId`: `Long`

示例 JSON：
```json
{
  "notificationId": 0
}
```

### `OperationResultDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/common/OperationResultDTO.java`
- 字段：
  - `success`: `boolean`
  - `id`: `Long`
  - `status`: `String`
  - `message`: `String`

示例 JSON：
```json
{
  "success": false,
  "id": 0,
  "status": "string",
  "message": "string"
}
```

### `PinCommentRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/PinCommentRequestDTO.java`
- 字段：
  - `commentId`: `Long`
  - `postId`: `Long`

示例 JSON：
```json
{
  "commentId": 0,
  "postId": 0
}
```

### `PublishAttemptResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/PublishAttemptResponseDTO.java`
- 字段：
  - `attemptId`: `Long`
  - `postId`: `Long`
  - `userId`: `Long`
  - `idempotentToken`: `String`
  - `transcodeJobId`: `String`
  - `attemptStatus`: `Integer`
  - `riskStatus`: `Integer`
  - `transcodeStatus`: `Integer`
  - `publishedVersionNum`: `Integer`
  - `errorCode`: `String`
  - `errorMessage`: `String`
  - `createTime`: `Long`
  - `updateTime`: `Long`

示例 JSON：
```json
{
  "attemptId": 0,
  "postId": 0,
  "userId": 0,
  "idempotentToken": "string",
  "transcodeJobId": "string",
  "attemptStatus": 0,
  "riskStatus": 0,
  "transcodeStatus": 0,
  "publishedVersionNum": 0,
  "errorCode": "string",
  "errorMessage": "string",
  "createTime": 0,
  "updateTime": 0
}
```

### `PublishContentRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/PublishContentRequestDTO.java`
- 字段：
  - `postId`: `Long`
  - `userId`: `Long`
  - `title`: `String`
  - `text`: `String`
  - `mediaInfo`: `String`
  - `location`: `String`
  - `visibility`: `String`
  - `postTypes`: `List<String>`

示例 JSON：
```json
{
  "postId": 0,
  "userId": 0,
  "title": "string",
  "text": "string",
  "mediaInfo": "string",
  "location": "string",
  "visibility": "string",
  "postTypes": [
    "string"
  ]
}
```

### `PublishContentResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/PublishContentResponseDTO.java`
- 字段：
  - `postId`: `Long`
  - `attemptId`: `Long`
  - `versionNum`: `Integer`
  - `status`: `String`

示例 JSON：
```json
{
  "postId": 0,
  "attemptId": 0,
  "versionNum": 0,
  "status": "string"
}
```

### `ReactionRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionRequestDTO.java`
- 字段：
  - `requestId`: `String`
  - `targetId`: `Long`
  - `targetType`: `String`
  - `type`: `String`
  - `action`: `String`

示例 JSON：
```json
{
  "requestId": "string",
  "targetId": 0,
  "targetType": "string",
  "type": "string",
  "action": "string"
}
```

### `ReactionResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionResponseDTO.java`
- 字段：
  - `requestId`: `String`
  - `currentCount`: `Long`
  - `success`: `boolean`

示例 JSON：
```json
{
  "requestId": "string",
  "currentCount": 0,
  "success": false
}
```

### `ReactionStateResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionStateResponseDTO.java`
- 字段：
  - `state`: `boolean`
  - `currentCount`: `Long`

示例 JSON：
```json
{
  "state": false,
  "currentCount": 0
}
```

### `RelationListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationListResponseDTO.java`
- 字段：
  - `items`: `List<RelationUserDTO>`
  - `nextCursor`: `String`

示例 JSON：
```json
{
  "items": [
    {
      "userId": 0,
      "nickname": "string",
      "avatar": "string",
      "followTime": 0
    }
  ],
  "nextCursor": "string"
}
```

### `RelationStateBatchRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationStateBatchRequestDTO.java`
- 字段：
  - `targetUserIds`: `List<Long>`

示例 JSON：
```json
{
  "targetUserIds": [
    0
  ]
}
```

### `RelationStateBatchResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationStateBatchResponseDTO.java`
- 字段：
  - `followingUserIds`: `List<Long>`
  - `blockedUserIds`: `List<Long>`

示例 JSON：
```json
{
  "followingUserIds": [
    0
  ],
  "blockedUserIds": [
    0
  ]
}
```

### `RelationUserDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationUserDTO.java`
- 字段：
  - `userId`: `Long`
  - `nickname`: `String`
  - `avatar`: `String`
  - `followTime`: `Long`

示例 JSON：
```json
{
  "userId": 0,
  "nickname": "string",
  "avatar": "string",
  "followTime": 0
}
```

### `RiskActionDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskActionDTO.java`
- 字段：
  - `type`: `String`
  - `params`: `String`

示例 JSON：
```json
{
  "type": "string",
  "params": "string"
}
```

### `RiskAppealRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskAppealRequestDTO.java`
- 字段：
  - `decisionId`: `Long`
  - `punishId`: `Long`
  - `content`: `String`

示例 JSON：
```json
{
  "decisionId": 0,
  "punishId": 0,
  "content": "string"
}
```

### `RiskAppealResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskAppealResponseDTO.java`
- 字段：
  - `appealId`: `Long`
  - `status`: `String`

示例 JSON：
```json
{
  "appealId": 0,
  "status": "string"
}
```

### `RiskCaseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskCaseDTO.java`
- 字段：
  - `caseId`: `Long`
  - `decisionId`: `Long`
  - `status`: `String`
  - `queue`: `String`
  - `assignee`: `Long`
  - `result`: `String`
  - `evidenceJson`: `String`
  - `createTime`: `Long`
  - `updateTime`: `Long`

示例 JSON：
```json
{
  "caseId": 0,
  "decisionId": 0,
  "status": "string",
  "queue": "string",
  "assignee": 0,
  "result": "string",
  "evidenceJson": "string",
  "createTime": 0,
  "updateTime": 0
}
```

### `RiskCaseListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskCaseListResponseDTO.java`
- 字段：
  - `cases`: `List<RiskCaseDTO>`

示例 JSON：
```json
{
  "cases": [
    {
      "caseId": 0,
      "decisionId": 0,
      "status": "string",
      "queue": "string",
      "assignee": 0,
      "result": "string",
      "evidenceJson": "string",
      "createTime": 0,
      "updateTime": 0
    }
  ]
}
```

### `RiskDecisionLogDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskDecisionLogDTO.java`
- 字段：
  - `decisionId`: `Long`
  - `eventId`: `String`
  - `userId`: `Long`
  - `actionType`: `String`
  - `scenario`: `String`
  - `result`: `String`
  - `reasonCode`: `String`
  - `signalsJson`: `String`
  - `actionsJson`: `String`
  - `extJson`: `String`
  - `traceId`: `String`
  - `createTime`: `Long`
  - `updateTime`: `Long`

示例 JSON：
```json
{
  "decisionId": 0,
  "eventId": "string",
  "userId": 0,
  "actionType": "string",
  "scenario": "string",
  "result": "string",
  "reasonCode": "string",
  "signalsJson": "string",
  "actionsJson": "string",
  "extJson": "string",
  "traceId": "string",
  "createTime": 0,
  "updateTime": 0
}
```

### `RiskDecisionLogListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskDecisionLogListResponseDTO.java`
- 字段：
  - `decisions`: `List<RiskDecisionLogDTO>`

示例 JSON：
```json
{
  "decisions": [
    {
      "decisionId": 0,
      "eventId": "string",
      "userId": 0,
      "actionType": "string",
      "scenario": "string",
      "result": "string",
      "reasonCode": "string",
      "signalsJson": "string",
      "actionsJson": "string",
      "extJson": "string",
      "traceId": "string",
      "createTime": 0,
      "updateTime": 0
    }
  ]
}
```

### `RiskDecisionRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskDecisionRequestDTO.java`
- 字段：
  - `eventId`: `String`
  - `actionType`: `String`
  - `scenario`: `String`
  - `contentText`: `String`
  - `mediaUrls`: `List<String>`
  - `targetId`: `String`
  - `ext`: `String`

示例 JSON：
```json
{
  "eventId": "string",
  "actionType": "string",
  "scenario": "string",
  "contentText": "string",
  "mediaUrls": [
    "string"
  ],
  "targetId": "string",
  "ext": "string"
}
```

### `RiskDecisionResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskDecisionResponseDTO.java`
- 字段：
  - `decisionId`: `Long`
  - `result`: `String`
  - `reasonCode`: `String`
  - `actions`: `List<RiskActionDTO>`
  - `signals`: `List<RiskSignalDTO>`

示例 JSON：
```json
{
  "decisionId": 0,
  "result": "string",
  "reasonCode": "string",
  "actions": [
    {
      "type": "string",
      "params": "string"
    }
  ],
  "signals": [
    {
      "source": "string",
      "name": "string",
      "score": 0.0,
      "tags": [
        "string"
      ]
    }
  ]
}
```

### `RiskPromptVersionDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPromptVersionDTO.java`
- 字段：
  - `version`: `Long`
  - `contentType`: `String`
  - `status`: `String`
  - `model`: `String`
  - `createBy`: `Long`
  - `publishBy`: `Long`
  - `publishTime`: `Long`
  - `createTime`: `Long`
  - `updateTime`: `Long`
  - `promptText`: `String`

示例 JSON：
```json
{
  "version": 0,
  "contentType": "string",
  "status": "string",
  "model": "string",
  "createBy": 0,
  "publishBy": 0,
  "publishTime": 0,
  "createTime": 0,
  "updateTime": 0,
  "promptText": "string"
}
```

### `RiskPromptVersionListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPromptVersionListResponseDTO.java`
- 字段：
  - `activeTextVersion`: `Long`
  - `activeImageVersion`: `Long`
  - `versions`: `List<RiskPromptVersionDTO>`

示例 JSON：
```json
{
  "activeTextVersion": 0,
  "activeImageVersion": 0,
  "versions": [
    {
      "version": 0,
      "contentType": "string",
      "status": "string",
      "model": "string",
      "createBy": 0,
      "publishBy": 0,
      "publishTime": 0,
      "createTime": 0,
      "updateTime": 0,
      "promptText": "string"
    }
  ]
}
```

### `RiskPromptVersionRollbackRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPromptVersionRollbackRequestDTO.java`
- 字段：
  - `toVersion`: `Long`

示例 JSON：
```json
{
  "toVersion": 0
}
```

### `RiskPromptVersionUpsertRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPromptVersionUpsertRequestDTO.java`
- 字段：
  - `version`: `Long`
  - `contentType`: `String`
  - `promptText`: `String`
  - `model`: `String`

示例 JSON：
```json
{
  "version": 0,
  "contentType": "string",
  "promptText": "string",
  "model": "string"
}
```

### `RiskPromptVersionUpsertResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPromptVersionUpsertResponseDTO.java`
- 字段：
  - `version`: `Long`
  - `status`: `String`

示例 JSON：
```json
{
  "version": 0,
  "status": "string"
}
```

### `RiskPunishmentApplyRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPunishmentApplyRequestDTO.java`
- 字段：
  - `userId`: `Long`
  - `type`: `String`
  - `decisionId`: `Long`
  - `reasonCode`: `String`
  - `startTime`: `Long`
  - `endTime`: `Long`
  - `durationSeconds`: `Long`

示例 JSON：
```json
{
  "userId": 0,
  "type": "string",
  "decisionId": 0,
  "reasonCode": "string",
  "startTime": 0,
  "endTime": 0,
  "durationSeconds": 0
}
```

### `RiskPunishmentDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPunishmentDTO.java`
- 字段：
  - `punishId`: `Long`
  - `userId`: `Long`
  - `type`: `String`
  - `status`: `String`
  - `startTime`: `Long`
  - `endTime`: `Long`
  - `reasonCode`: `String`
  - `decisionId`: `Long`
  - `operatorId`: `Long`
  - `createTime`: `Long`
  - `updateTime`: `Long`

示例 JSON：
```json
{
  "punishId": 0,
  "userId": 0,
  "type": "string",
  "status": "string",
  "startTime": 0,
  "endTime": 0,
  "reasonCode": "string",
  "decisionId": 0,
  "operatorId": 0,
  "createTime": 0,
  "updateTime": 0
}
```

### `RiskPunishmentListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPunishmentListResponseDTO.java`
- 字段：
  - `punishments`: `List<RiskPunishmentDTO>`

示例 JSON：
```json
{
  "punishments": [
    {
      "punishId": 0,
      "userId": 0,
      "type": "string",
      "status": "string",
      "startTime": 0,
      "endTime": 0,
      "reasonCode": "string",
      "decisionId": 0,
      "operatorId": 0,
      "createTime": 0,
      "updateTime": 0
    }
  ]
}
```

### `RiskPunishmentRevokeRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskPunishmentRevokeRequestDTO.java`
- 字段：
  - `punishId`: `Long`

示例 JSON：
```json
{
  "punishId": 0
}
```

### `RiskRuleVersionDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskRuleVersionDTO.java`
- 字段：
  - `version`: `Long`
  - `status`: `String`
  - `createBy`: `Long`
  - `publishBy`: `Long`
  - `publishTime`: `Long`
  - `createTime`: `Long`
  - `updateTime`: `Long`
  - `rulesJson`: `String`

示例 JSON：
```json
{
  "version": 0,
  "status": "string",
  "createBy": 0,
  "publishBy": 0,
  "publishTime": 0,
  "createTime": 0,
  "updateTime": 0,
  "rulesJson": "string"
}
```

### `RiskRuleVersionListResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskRuleVersionListResponseDTO.java`
- 字段：
  - `activeVersion`: `Long`
  - `versions`: `List<RiskRuleVersionDTO>`

示例 JSON：
```json
{
  "activeVersion": 0,
  "versions": [
    {
      "version": 0,
      "status": "string",
      "createBy": 0,
      "publishBy": 0,
      "publishTime": 0,
      "createTime": 0,
      "updateTime": 0,
      "rulesJson": "string"
    }
  ]
}
```

### `RiskRuleVersionRollbackRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskRuleVersionRollbackRequestDTO.java`
- 字段：
  - `toVersion`: `Long`

示例 JSON：
```json
{
  "toVersion": 0
}
```

### `RiskRuleVersionUpsertRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskRuleVersionUpsertRequestDTO.java`
- 字段：
  - `version`: `Long`
  - `rulesJson`: `String`

示例 JSON：
```json
{
  "version": 0,
  "rulesJson": "string"
}
```

### `RiskRuleVersionUpsertResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/admin/dto/RiskRuleVersionUpsertResponseDTO.java`
- 字段：
  - `version`: `Long`
  - `status`: `String`

示例 JSON：
```json
{
  "version": 0,
  "status": "string"
}
```

### `RiskSignalDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskSignalDTO.java`
- 字段：
  - `source`: `String`
  - `name`: `String`
  - `score`: `Double`
  - `tags`: `List<String>`

示例 JSON：
```json
{
  "source": "string",
  "name": "string",
  "score": 0.0,
  "tags": [
    "string"
  ]
}
```

### `RootCommentViewDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/RootCommentViewDTO.java`
- 字段：
  - `root`: `CommentViewDTO`
  - `repliesPreview`: `List<CommentViewDTO>`

示例 JSON：
```json
{
  "root": {
    "commentId": 0,
    "postId": 0,
    "userId": 0,
    "nickname": "string",
    "avatarUrl": "string",
    "rootId": 0,
    "parentId": 0,
    "replyToId": 0,
    "content": "string",
    "status": 0,
    "likeCount": 0,
    "replyCount": 0,
    "createTime": 0
  },
  "repliesPreview": [
    {
      "commentId": 0,
      "postId": 0,
      "userId": 0,
      "nickname": "string",
      "avatarUrl": "string",
      "rootId": 0,
      "parentId": 0,
      "replyToId": 0,
      "content": "string",
      "status": 0,
      "likeCount": 0,
      "replyCount": 0,
      "createTime": 0
    }
  ]
}
```

### `SaveDraftRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/SaveDraftRequestDTO.java`
- 字段：
  - `draftId`: `Long`
  - `userId`: `Long`
  - `title`: `String`
  - `contentText`: `String`
  - `mediaIds`: `List<String>`

示例 JSON：
```json
{
  "draftId": 0,
  "userId": 0,
  "title": "string",
  "contentText": "string",
  "mediaIds": [
    "string"
  ]
}
```

### `SaveDraftResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/SaveDraftResponseDTO.java`
- 字段：
  - `draftId`: `Long`

示例 JSON：
```json
{
  "draftId": 0
}
```

### `ScheduleAuditResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ScheduleAuditResponseDTO.java`
- 字段：
  - `taskId`: `Long`
  - `userId`: `Long`
  - `scheduleTime`: `Long`
  - `status`: `Integer`
  - `retryCount`: `Integer`
  - `isCanceled`: `Integer`
  - `lastError`: `String`
  - `alarmSent`: `Integer`
  - `contentData`: `String`

示例 JSON：
```json
{
  "taskId": 0,
  "userId": 0,
  "scheduleTime": 0,
  "status": 0,
  "retryCount": 0,
  "isCanceled": 0,
  "lastError": "string",
  "alarmSent": 0,
  "contentData": "string"
}
```

### `ScheduleCancelRequestDTO`

- 来源：`nexus-trigger/src/main/java/cn/nexus/trigger/http/social/dto/ScheduleCancelRequestDTO.java`
- 字段：
  - `taskId`: `Long`
  - `userId`: `Long`
  - `reason`: `String`

示例 JSON：
```json
{
  "taskId": 0,
  "userId": 0,
  "reason": "string"
}
```

### `ScheduleContentRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ScheduleContentRequestDTO.java`
- 字段：
  - `postId`: `Long`
  - `publishTime`: `Long`
  - `timezone`: `String`

示例 JSON：
```json
{
  "postId": 0,
  "publishTime": 0,
  "timezone": "string"
}
```

### `ScheduleContentResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ScheduleContentResponseDTO.java`
- 字段：
  - `taskId`: `Long`
  - `postId`: `Long`
  - `status`: `String`

示例 JSON：
```json
{
  "taskId": 0,
  "postId": 0,
  "status": "string"
}
```

### `ScheduleUpdateRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/ScheduleUpdateRequestDTO.java`
- 字段：
  - `taskId`: `Long`
  - `userId`: `Long`
  - `publishTime`: `Long`
  - `contentData`: `String`
  - `reason`: `String`

示例 JSON：
```json
{
  "taskId": 0,
  "userId": 0,
  "publishTime": 0,
  "contentData": "string",
  "reason": "string"
}
```

### `SearchItemDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchItemDTO.java`
- 字段：
  - `id`: `String`
  - `title`: `String`
  - `description`: `String`
  - `coverImage`: `String`
  - `tags`: `List<String>`
  - `authorAvatar`: `String`
  - `authorNickname`: `String`
  - `tagJson`: `String`
  - `likeCount`: `Long`
  - `favoriteCount`: `Long`
  - `liked`: `Boolean`
  - `faved`: `Boolean`
  - `isTop`: `Boolean`

示例 JSON：
```json
{
  "id": "string",
  "title": "string",
  "description": "string",
  "coverImage": "string",
  "tags": [
    "string"
  ],
  "authorAvatar": "string",
  "authorNickname": "string",
  "tagJson": "string",
  "likeCount": 0,
  "favoriteCount": 0,
  "liked": false,
  "faved": false,
  "isTop": false
}
```

### `SearchResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchResponseDTO.java`
- 字段：
  - `items`: `List<SearchItemDTO>`
  - `nextAfter`: `String`
  - `hasMore`: `boolean`

示例 JSON：
```json
{
  "items": [
    {
      "id": "string",
      "title": "string",
      "description": "string",
      "coverImage": "string",
      "tags": [
        "string"
      ],
      "authorAvatar": "string",
      "authorNickname": "string",
      "tagJson": "string",
      "likeCount": 0,
      "favoriteCount": 0,
      "liked": false,
      "faved": false,
      "isTop": false
    }
  ],
  "nextAfter": "string",
  "hasMore": false
}
```

### `SuggestResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/search/dto/SuggestResponseDTO.java`
- 字段：
  - `items`: `List<String>`

示例 JSON：
```json
{
  "items": [
    "string"
  ]
}
```

### `SystemHealthResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/dto/SystemHealthResponseDTO.java`
- 字段：
  - `status`: `String`

示例 JSON：
```json
{
  "status": "string"
}
```

### `TextScanRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/TextScanRequestDTO.java`
- 字段：
  - `content`: `String`
  - `userId`: `Long`
  - `scenario`: `String`

示例 JSON：
```json
{
  "content": "string",
  "userId": 0,
  "scenario": "string"
}
```

### `TextScanResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/TextScanResponseDTO.java`
- 字段：
  - `result`: `String`
  - `tags`: `List<String>`

示例 JSON：
```json
{
  "result": "string",
  "tags": [
    "string"
  ]
}
```

### `UploadSessionRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/UploadSessionRequestDTO.java`
- 字段：
  - `fileType`: `String`
  - `fileSize`: `Long`
  - `crc32`: `String`

示例 JSON：
```json
{
  "fileType": "string",
  "fileSize": 0,
  "crc32": "string"
}
```

### `UploadSessionResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/content/dto/UploadSessionResponseDTO.java`
- 字段：
  - `uploadUrl`: `String`
  - `token`: `String`
  - `sessionId`: `String`

示例 JSON：
```json
{
  "uploadUrl": "string",
  "token": "string",
  "sessionId": "string"
}
```

### `UserInternalUpsertRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserInternalUpsertRequestDTO.java`
- 字段：
  - `userId`: `Long`
  - `username`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`
  - `needApproval`: `Boolean`
  - `status`: `String`

示例 JSON：
```json
{
  "userId": 0,
  "username": "string",
  "nickname": "string",
  "avatarUrl": "string",
  "needApproval": false,
  "status": "string"
}
```

### `UserPrivacyResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserPrivacyResponseDTO.java`
- 字段：
  - `needApproval`: `Boolean`

示例 JSON：
```json
{
  "needApproval": false
}
```

### `UserPrivacyUpdateRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserPrivacyUpdateRequestDTO.java`
- 字段：
  - `needApproval`: `Boolean`

示例 JSON：
```json
{
  "needApproval": false
}
```

### `UserProfilePageResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserProfilePageResponseDTO.java`
- 字段：
  - `profile`: `UserProfileResponseDTO`
  - `relation`: `UserRelationStatsDTO`
  - `risk`: `UserRiskStatusResponseDTO`

示例 JSON：
```json
{
  "profile": {
    "userId": 0,
    "username": "string",
    "nickname": "string",
    "avatarUrl": "string",
    "status": "string"
  },
  "relation": {
    "followCount": 0,
    "followerCount": 0,
    "isFollow": false
  },
  "risk": {
    "status": "string",
    "capabilities": [
      "string"
    ]
  }
}
```

### `UserProfileResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserProfileResponseDTO.java`
- 字段：
  - `userId`: `Long`
  - `username`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`
  - `status`: `String`

示例 JSON：
```json
{
  "userId": 0,
  "username": "string",
  "nickname": "string",
  "avatarUrl": "string",
  "status": "string"
}
```

### `UserProfileUpdateRequestDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserProfileUpdateRequestDTO.java`
- 字段：
  - `nickname`: `String`
  - `avatarUrl`: `String`

示例 JSON：
```json
{
  "nickname": "string",
  "avatarUrl": "string"
}
```

### `UserRelationStatsDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/user/dto/UserRelationStatsDTO.java`
- 字段：
  - `followCount`: `long`
  - `followerCount`: `long`
  - `isFollow`: `boolean`

示例 JSON：
```json
{
  "followCount": 0,
  "followerCount": 0,
  "isFollow": false
}
```

### `UserRiskStatusResponseDTO`

- 来源：`nexus-api/src/main/java/cn/nexus/api/social/risk/dto/UserRiskStatusResponseDTO.java`
- 字段：
  - `status`: `String`
  - `capabilities`: `List<String>`

示例 JSON：
```json
{
  "status": "string",
  "capabilities": [
    "string"
  ]
}
```

<!-- DTO-DICT:END -->
