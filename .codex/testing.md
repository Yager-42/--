# 测试记录（执行者：Codex / 日期：2026-01-13）

本次交付落地了 Feed Phase 2（在线推 / 离线拉）代码改动；按用户要求，未执行本地编译与自动化测试。

建议后续补齐并执行（需要 MySQL + Redis + RabbitMQ）：
- Timeline 分页稳定性测试（同时间戳多条写入，翻页不重复/不漏）
- fanout 幂等性测试（重复消费同一 Post_Published 事件）
- 负反馈过滤测试（SADD/SREM 后读侧可见性变化）
- Phase 2 在线推/离线拉：inbox key 过期后不再接收 fanout；回归首页触发 rebuild 并返回近况

## 2026-01-13

本次交付为“点赞链路实现方案文档”，未修改业务代码，未执行自动化测试。

建议在开始实现点赞链路时补齐并执行：
- Reaction 幂等测试：同一用户重复 ADD/REMOVE，不应重复计数
- 并发测试：同一 target 热点并发下，Redis count 不为负且与 DB 最终一致
- flush 竞态测试：flush 过程中发生新点赞，不应出现“最后一次写丢失”
