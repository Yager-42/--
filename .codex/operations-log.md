# .codex/operations-log.md

日期：2026-01-21  
执行者：Codex（Linus-mode）

## 通知业务实现（按 .codex/notification-business-implementation.md#10）

- 追加 DDL：`interaction_notification`（聚合收件箱）、`interaction_notify_inbox`（消费幂等）到 `project/nexus/docs/social_schema.sql`
- 新增统一事件：`InteractionNotifyEvent` + `EventType`（LIKE_ADDED/COMMENT_CREATED/COMMENT_MENTIONED）
- 点赞链路：`ReactionLikeService.applyReaction` 在 `delta==+1` 发布 `LIKE_ADDED`
- 评论链路：`InteractionService.comment` 落库后发布 `COMMENT_CREATED`；并从文本解析 `@username` 发布 `COMMENT_MENTIONED`
- 通知消费者：`InteractionNotifyConsumer` 先 inbox 幂等去重，再聚合 UPSERT 写入 `interaction_notification`
- 修正 inbox payload：`InteractionNotifyConsumer` 写入 `interaction_notify_inbox.payload` 改为可复用 JSON（失败则允许为空，不影响幂等）
- 读接口：`GET /api/v1/notification/list` 改为真实分页读取 + 渲染 title/content
- 已读接口：新增 `POST /api/v1/notification/read` 与 `POST /api/v1/notification/read/all`
- 清理“mentions 契约残留”：移除 `CommentRequestDTO.mentions` 与 `InteractionService.comment(..., mentions)`；Controller 不再透传（兼容旧客户端：忽略未知字段）
- 文档同步：`.codex/comment-floor-system-implementation.md` 的 curl 示例删除 `mentions`；`社交接口.md` 的接口表删除 `mentions` 并补充通知已读接口
- 修正通知 inbox 幂等：`InteractionNotifyInboxMapper.xml` 改用 `INSERT IGNORE`，避免 `ON DUPLICATE KEY` 触发 `update_time` 自更新导致幂等失效
