# Nexus 后端接口文档（给前端对接）

> 生成来源：代码扫描（`project/nexus/nexus-trigger` 里所有 `@RestController`）。
> 重新生成：`python project/nexus/docs/tools/gen_frontend_api_doc.py`
> 如果你说的“项目”不是 `project/nexus`，把目录名告诉我，我会重新生成。

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
  - `/api/v1/auth/login/sms`
  - `/api/v1/auth/register`
  - `/api/v1/auth/sms/send`
  - `/api/v1/health`
  - `/api/v1/health/**`

## 1) 接口总表（可直接当对接清单）

| 方法 | 路径 | 需登录 | 需角色 | Controller#method | 请求体 | 响应 data |
|---|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/admin/grant` | 是 | `ADMIN` | `AuthController#grantAdmin` | `AuthGrantAdminRequestDTO` | `` |
| `POST` | `/api/v1/auth/admin/revoke` | 是 | `ADMIN` | `AuthController#revokeAdmin` | `AuthGrantAdminRequestDTO` | `` |
| `GET` | `/api/v1/auth/admins` | 是 | `ADMIN` | `AuthController#listAdmins` | `` | `AuthAdminListResponseDTO` |
| `POST` | `/api/v1/auth/login/password` | 否 | `` | `AuthController#passwordLogin` | `AuthPasswordLoginRequestDTO` | `AuthTokenResponseDTO` |
| `POST` | `/api/v1/auth/login/sms` | 否 | `` | `AuthController#smsLogin` | `AuthSmsLoginRequestDTO` | `AuthTokenResponseDTO` |
| `POST` | `/api/v1/auth/logout` | 是 | `` | `AuthController#logout` | `` | `` |
| `GET` | `/api/v1/auth/me` | 是 | `` | `AuthController#me` | `` | `AuthMeResponseDTO` |
| `POST` | `/api/v1/auth/password/change` | 是 | `` | `AuthController#changePassword` | `AuthChangePasswordRequestDTO` | `` |
| `POST` | `/api/v1/auth/register` | 否 | `` | `AuthController#register` | `AuthRegisterRequestDTO` | `AuthRegisterResponseDTO` |
| `POST` | `/api/v1/auth/sms/send` | 否 | `` | `AuthController#sendSms` | `AuthSmsSendRequestDTO` | `AuthSmsSendResponseDTO` |
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
| `POST` | `/api/v1/group/channel/config` | 是 | `` | `CommunityController#channelConfig` | `ChannelConfigRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/group/join` | 是 | `` | `CommunityController#join` | `GroupJoinRequestDTO` | `GroupJoinResponseDTO` |
| `POST` | `/api/v1/group/member/kick` | 是 | `` | `CommunityController#kick` | `GroupKickRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/group/member/role` | 是 | `` | `CommunityController#changeRole` | `GroupRoleRequestDTO` | `OperationResultDTO` |
| `GET` | `/api/v1/health` | 否 | `` | `SystemHealthController#health` | `` | `SystemHealthResponseDTO` |
| `POST` | `/api/v1/interact/comment` | 是 | `` | `InteractionController#comment` | `CommentRequestDTO` | `CommentResponseDTO` |
| `POST` | `/api/v1/interact/comment/pin` | 是 | `` | `InteractionController#pinComment` | `PinCommentRequestDTO` | `OperationResultDTO` |
| `POST` | `/api/v1/interact/reaction` | 是 | `` | `InteractionController#react` | `ReactionRequestDTO` | `ReactionResponseDTO` |
| `GET` | `/api/v1/interact/reaction/likers` | 是 | `` | `InteractionController#reactionLikers` | `` | `ReactionLikersResponseDTO` |
| `GET` | `/api/v1/interact/reaction/state` | 是 | `` | `InteractionController#reactionState` | `` | `ReactionStateResponseDTO` |
| `POST` | `/api/v1/interaction/poll/create` | 是 | `` | `InteractionController#createPoll` | `PollCreateRequestDTO` | `PollCreateResponseDTO` |
| `POST` | `/api/v1/interaction/poll/vote` | 是 | `` | `InteractionController#vote` | `PollVoteRequestDTO` | `PollVoteResponseDTO` |
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
| `GET` | `/api/v1/wallet/balance` | 是 | `` | `InteractionController#balance` | `` | `WalletBalanceResponseDTO` |
| `POST` | `/api/v1/wallet/tip` | 是 | `` | `InteractionController#tip` | `TipRequestDTO` | `TipResponseDTO` |
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

#### `GET /api/v1/interact/reaction/likers`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `targetId`: `Long`
  - `targetType`: `String`
  - `type`: `String`
  - `cursor`: `String`
  - `limit`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `items`: `List<ReactionLikerDTO>`
    - `nextCursor`: `String`
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

#### `POST /api/v1/interaction/poll/create`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`PollCreateRequestDTO`
  - `question`: `String`
  - `options`: `List<String>`
  - `allowMulti`: `Boolean`
  - `expireSeconds`: `Integer`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `pollId`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/interaction/poll/vote`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`PollVoteRequestDTO`
  - `pollId`: `Long`
  - `optionIds`: `List<Long>`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `updatedStats`: `String`
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

#### `GET /api/v1/wallet/balance`

- 鉴权：需要登录
- Query 参数（来自 DTO 字段）：
  - `currencyType`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `currencyType`: `String`
    - `amount`: `String`
    - `frozenAmount`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

#### `POST /api/v1/wallet/tip`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`TipRequestDTO`
  - `toUserId`: `Long`
  - `amount`: `BigDecimal`
  - `currency`: `String`
  - `postId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `txId`: `String`
    - `effectUrl`: `String`
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

#### `POST /api/v1/group/channel/config`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`ChannelConfigRequestDTO`
  - `channelId`: `Long`
  - `slowModeInterval`: `Integer`
  - `locked`: `Boolean`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommunityController.java`

#### `POST /api/v1/group/join`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`GroupJoinRequestDTO`
  - `groupId`: `Long`
  - `userId`: `Long`
  - `answers`: `String`
  - `inviteToken`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `status`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommunityController.java`

#### `POST /api/v1/group/member/kick`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`GroupKickRequestDTO`
  - `groupId`: `Long`
  - `targetId`: `Long`
  - `reason`: `String`
  - `ban`: `Boolean`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommunityController.java`

#### `POST /api/v1/group/member/role`

- 鉴权：需要登录
- 请求体（JSON）：
  - 类型：`GroupRoleRequestDTO`
  - `groupId`: `Long`
  - `targetId`: `Long`
  - `roleId`: `Long`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `success`: `boolean`
    - `id`: `Long`
    - `status`: `String`
    - `message`: `String`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommunityController.java`

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

#### `POST /api/v1/auth/login/sms`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`AuthSmsLoginRequestDTO`
  - `phone`: `String`
  - `smsCode`: `String`
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
  - `smsCode`: `String`
  - `password`: `String`
  - `nickname`: `String`
  - `avatarUrl`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `userId`: `Long`
- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

#### `POST /api/v1/auth/sms/send`

- 鉴权：匿名可调用
- 请求体（JSON）：
  - 类型：`AuthSmsSendRequestDTO`
  - `phone`: `String`
  - `bizType`: `String`
- 响应：统一 `Response` 壳，成功 `code=0000`
  - `data` 字段：
    - `expireSeconds`: `Integer`
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

