# 验证说明（执行者：Codex / 日期：2026-01-15）

## 本次交付内容

- `.codex/context-scan.json`：项目结构/技术栈/现状扫描结果。
- `.codex/distribution-feed-implementation.md`：分发与 Feed 服务实现方案（MVP → 优化 → 排序推荐，已落地 10.5.1 ~ 10.5.7 + 10.6.1/10.6.2/10.6.4/10.6.5）。
- `.codex/distribution-feed-implementation-ezRead.md`：当前代码实现快照说明（Phase 1 + Phase 2 + 10.5.2~10.5.7 快照）。
- 负反馈类型语义修复与落地：负反馈“类型”指 postTypes（业务类目/主题），来源 `content_post_type`；发布接口支持用户提交 postTypes（最多 5 个）并落库；负反馈写入会校验 `type` 是否属于该帖 postTypes，并通过 Redis HASH `feed:neg:postTypeByPost:{userId}` 支持撤销反查。
- Feed 10.6 可改进点补齐：MQ 消息统一 JSON、timeline 负反馈 postId 批量过滤、fanout 补齐 DLQ 与最小指标日志、fanout 在线判定改为 Redis pipeline（批量 EXISTS）。
- `.codex/interaction-like-pipeline-implementation.md`：点赞/取消点赞计数方案（Redis 秒回 + 延迟落库 + 实时监控 + 离线分析，已补齐读链路、收敛 Redis 数据结构，并补充“热点探测 + L1 Caffeine + 可选 TaiShan KV”章节）。
- `.codex/operations-log.md`：关键决策记录。
- `.codex/review-report.md`：自检审查报告。
- `.codex/testing.md`：测试记录（按用户要求不做 Maven 验证，仅记录静态自检与建议验收步骤）。

## 你如何快速验收（不需要任何技术背景）

1. 打开 `.codex/distribution-feed-implementation.md`，只检查 “Phase 2 验收点” 那 3 条是否是你想要的用户体验。
2. 打开 `.codex/distribution-feed-implementation-ezRead.md`，确认“本次完善（已落地）”与“剩余不足”是否符合预期。
3. 打开 `.codex/distribution-feed-implementation.md` 的“负反馈”小节，确认：类型维度写的是 `postTypes`（业务类目/主题，来源 `content_post_type`），并且撤销需要 `feed:neg:postTypeByPost:{userId}` 反查，而不是把 `media_type`（图文/视频）当内容类型。

补充（10.5.1）：打开 `.codex/distribution-feed-implementation.md` 的 `10.5.1`，只看两件事：
1) 是否明确“拆片（dispatcher）”与“执行片（worker）”的职责边界？（不允许一个 consumer 做完全部 fanout）
2) 是否明确“失败只重试切片”的粒度与幂等点？（ZSET member 幂等）

补充（10.5.2~10.5.7）：打开 `.codex/distribution-feed-implementation-ezRead.md`，只看三件事：
1) follow 补偿：关注成功后（ACTIVE）在线用户是否立刻看到新关注者最近内容？
2) 大 V 隔离：大 V 发布是否“只写 outbox/铁粉”，读侧是否能合并读取到？
3) Max_ID + 懒清理：翻页是否稳定推进；读时发现无效内容是否会顺手清掉索引，避免反复 miss？

补充（点赞链路）：打开 `.codex/interaction-like-pipeline-implementation.md`，只看三件事：
1) 你是否接受“先写 Redis 秒回，几分钟后再批量落库”的一致性策略？
2) 你能否回答文末“开放问题”里关于 `targetType` 范围 / 窗口长度 / Redis 版本 的选择？（不回答会卡实现）
3) 你是否认可“分步拆分（10.4）”的推进顺序：先把写/flush/读跑通，再做点赞通知 MVP（异步，不阻塞点赞接口），最后再做通知聚合（可选）？

再补充一眼（你不需要懂代码）：
- 读链路是否满足你的产品体验：列表页需要一次性批量拿到 `likeCount + likedByMe`（见文档 5.3/5.4）
- 写链路是否“干净”：窗口状态只用一个 `like:win:*` key；flush 用 `RENAMENX` 快照，不会把 flush 期间的新点赞误删
