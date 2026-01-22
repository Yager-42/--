# .codex/operations-log.md

日期：2026-01-21  
执行者：Codex（Linus-mode）

## 通知业务实现（按 .codex/notification-business-implementation.md#10）

- 追加 DDL：`interaction_notification`（聚合收件箱）、`interaction_notify_inbox`（消费幂等）到 `project/nexus/docs/social_schema.sql`
- 新增统一事件：`InteractionNotifyEvent` + `EventType`（LIKE_ADDED/COMMENT_CREATED/COMMENT_MENTIONED）
- 点赞链路：`ReactionLikeService.applyReaction` 在 `delta==+1` 发布 `LIKE_ADDED`
- 评论链路：`InteractionService.comment` 落库后发布 `COMMENT_CREATED`；并从文本解析 `@username` 发布 `COMMENT_MENTIONED`
- 通知消费者：`InteractionNotifyConsumer` 先 inbox 幂等去重，再聚合 UPSERT 写入 `interaction_notification`
- 修正 inbox payload：`InteractionNotifyConsumer` 写入 `interaction_notify_inbox.payload` 改为可复用 JSON（序列化失败则写入最小 JSON 标记，不影响幂等）
- 读接口：`GET /api/v1/notification/list` 改为真实分页读取 + 渲染 title/content
- 已读接口：新增 `POST /api/v1/notification/read` 与 `POST /api/v1/notification/read/all`
- 清理“mentions 契约残留”：移除 `CommentRequestDTO.mentions` 与 `InteractionService.comment(..., mentions)`；Controller 不再透传（兼容旧客户端：忽略未知字段）
- 文档同步：`.codex/comment-floor-system-implementation.md` 的 curl 示例删除 `mentions`；`社交接口.md` 的接口表删除 `mentions` 并补充通知已读接口
- 修正通知 inbox 幂等：`InteractionNotifyInboxMapper.xml` 改用 `INSERT IGNORE`，避免 `ON DUPLICATE KEY` 触发 `update_time` 自更新导致幂等失效
- 修正消费者“假成功”问题：`InteractionNotifyConsumer` 对缺失核心字段的事件直接抛错并标记 FAIL（坏数据不允许悄悄吞掉）

---

日期：2026-01-22  \
执行者：Codex（Linus-mode）

## 评论热榜/点赞回写补齐（按 .codex/comment-floor-system-implementation.md）

- 追加 DDL：`interaction_comment`、`interaction_comment_pin` 到 `project/nexus/docs/social_schema.sql`（字段/索引与 MyBatis Mapper 对齐）
- 点赞→热榜回写：`ReactionResultVO` 增加 `delta`；`InteractionService.react` 在 `targetType=COMMENT && delta!=0` 时发布 `CommentLikeChangedEvent`
- 热榜重建：新增 `CommentHotRankRebuildService.rebuildForPost`（清空并重建 ZSET，支持 trim topK）；`ICommentRepository` 新增 `listRecentRootBriefs`；`ICommentHotRankRepository` 新增 `clear/trimToTop`
- 手动触发：新增 `CommentHotRankRebuildRunner`，仅在启动参数 `--comment.hot.rebuild.postId=<postId>` 时执行一次
- 本地验证：下载 Maven 3.9.6 到 `.codex/tools/apache-maven-3.9.6`，执行 `mvn -DskipTests package` 通过
