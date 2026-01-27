# verification.md

日期：2026-01-20  
执行者：Codex（Linus-mode）

## 点赞业务（三条链路）交付物验收

### 链路 1：在线写入（HTTP -> Redis）

- [x] 写接口：`POST /api/v1/interact/reaction` 已实现真实链路（不再是占位返回 0/1）。
- [x] 幂等语义：只支持 set-state（ADD/REMOVE），重复请求不会把计数算飞。
- [x] `requestId`：请求可选传入，服务端必回传；并写入结构化日志用于串联排障。

### 链路 2：延迟落库（RabbitMQ 延迟队列 -> DB）

- [x] 延迟队列：x-delayed-message 拓扑已落地（exchange/queue/routingKey + DLQ）。
- [x] 消费者：Redis 锁 + attempt 重投递（抢不到锁/短暂失败不吞消息）。
- [x] 同步策略：opsKey -> processingKey 快照（Lua + RENAME）避免并发丢更新；批量 upsert/delete 事实表；覆盖写 count 表。

### 链路 3：实时监控/热榜/动态窗口（日志 -> Kafka -> Redis）

- [x] 结构化日志：在线写成功后打印 `event=reaction_like` 的 JSON 行（字段对齐说明书 13.1）。
- [x] 回写消费者：消费 `topic_like_5m_agg` 回写 Redis 热榜 `hot:like:5m:{targetType}` 并写入 `window_ms`（EX=60s）。
- [x] 告警消费者：消费 `topic_like_hot_alert`，最小告警 `log.warn(...)`。

## 验收与自测

- [x] 验收说明书：`.codex/interaction-like-pipeline-implementation.md`（第 16/17 章）。
- [x] 关键决策留痕：`.codex/operations-log.md`。
- [x] 代码审查报告：`.codex/review-report.md`。
- [x] 最小自测清单：`.codex/testing.md`（不依赖 Maven）。

---

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 分发/Feed 缺口补齐验收（不做 Phase 3 推荐）

- [x] unfollow：新增 `POST /api/v1/relation/unfollow`，并通过 `RelationFollowEvent(status=UNFOLLOW)` 驱动 Feed 侧立刻生效（在线用户强制重建 inbox）
- [x] 铁粉集合生成：消费 `interaction.notify`（`LIKE_ADDED(仅 POST)`/`COMMENT_CREATED`）自动写入 `feed:corefans:{authorId}`，并刷新 TTL
- [x] 热点回表 L1：`ContentRepository` 对热点 postId 启用 `JD HotKey + Caffeine` 短 TTL 缓存；写路径会 invalidate
- [x] 本地编译验证：`project/nexus` 下执行 `mvn -DskipTests package` 通过



---

日期：2026-01-26  
执行者：Codex（Linus-mode）

## Phase 3 推荐流验收（RECOMMEND/POPULAR/NEIGHBORS + Gorse + fallback）

- [x] 零破坏性：FOLLOW 用户可见行为保持不变（cursor 仍为 postId）；推荐流走独立分支与不透明 token。
- [x] 本地编译验证：`project/nexus` 下执行 `mvn -DskipTests package`，BUILD SUCCESS（Finished at: 2026-01-26T12:18:19+08:00）。
- [ ] 翻页稳定：RECOMMEND 连续翻 5 页，不重复、不漏页；同 cursor 重试返回一致（见 `.codex/testing.md`）。
- [ ] 负反馈生效：postId 与 postType 均能过滤（见 `.codex/testing.md`）。
- [ ] 下架不穿透：status!=2 的内容不返回（见 `.codex/testing.md`）。
- [ ] 推荐挂了不白屏：gorse 不可达降级 latest 且可翻页（见 `.codex/testing.md`）。

---

日期：2026-01-27  
执行者：Codex（Linus-mode）

## 风控与信任服务实现方案（文档）验收

- [x] 新文档：`风控与信任服务-实现方案.md` 已生成（Production 上线口径；范围=文本+图片；图片风控=多模态 LLM，OCR+文本仅兜底；架构、数据结构、在线/异步流程、API/事件契约、存储、灰度、指标、上线验收与检查清单）。  
- [x] 文档一致性修正：`社交接口.md` 风控接口表已对齐代码路由前缀 `/api/v1`，并增加新文档入口链接。  
- [x] 留痕齐全：`.codex/context-scan.json`、`.codex/operations-log.md`、`.codex/review-report.md`、`.codex/testing.md` 已追加本次记录。  
- [ ] （可选后续）按文档的上线闭环落地代码：新增 `POST /api/v1/risk/decision` + `risk_decision_log/risk_case/risk_rule_version` 等，使“决策→处置→审计→人审→申诉/反馈”可跑通。  
