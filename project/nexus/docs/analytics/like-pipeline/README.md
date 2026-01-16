# 点赞实时/离线链路：外部配置占位（不写 Java 也能跑）

日期：2026-01-16  
执行者：Codex（Linus-mode）

## 你要解决的问题

链路 1/2（业务写入+延迟落库）需要写 Java。

但链路 3/4（实时监控+离线分析）**不需要写 Java** 也能跑起来：

- 你只要能把符合契约的 JSON 事件写进 Kafka（手工写也行）
- Flink 用 SQL 做 5 分钟窗口聚合，输出到另一个 Kafka topic
- Hive/Spark 只要跑 SQL，就能出日报/热榜

## 这个目录放什么

- `logstash/topic_like_monitor.conf`：从应用日志抽取 JSON -> Kafka(topic_like_monitor)
- `flink/reaction_like_5m_agg.sql`：Flink SQL：Kafka 输入 -> 5min 聚合 -> Kafka 输出
- `hive/dwd_like_monitor_hourly.sql`：Hive 小时分区表 DDL（占位）
- `spark/ads_like_hot_daily.sql`：Spark SQL：昨日 TopN 热点（占位）
- `prometheus/prometheus.yml`：Prometheus 抓取占位（可选）

注意：这些文件你需要按你的环境改几个变量（bootstrap servers、日志路径等）。

---

## A. 不写 Java 的最小跑通（推荐先跑这个）

### A1) 准备 Kafka

你至少需要 1 个 Kafka 集群（本地或远端都行）。

创建 3 个 topic（名字在文档里写死了）：

- `topic_like_monitor`（输入：点赞事件）
- `topic_like_5m_agg`（输出：5 分钟聚合）
- `topic_like_hot_alert`（输出：热点告警事件，可选）

### A2) 跑 Flink SQL（不写 Java）

把这个 SQL 文件跑起来：

- `flink/reaction_like_5m_agg.sql`

你只需要把里面的占位符替换成你的 Kafka 地址：

- `KAFKA_BOOTSTRAP_SERVERS`

### A3) 手工往 Kafka 写测试事件（不依赖应用服务）

你可以用 Kafka 自带的 console producer 往 `topic_like_monitor` 写一行 JSON。

JSON 必须符合字段契约（见 `.codex/interaction-like-pipeline-implementation.md` 的 13.1）。

最小可用示例（复制 3 行进去）：

```json
{"event":"reaction_like","ts":1736990000000,"userId":1,"targetType":"POST","targetId":90001,"reactionType":"LIKE","action":"ADD","desiredState":1,"delta":1,"currentCount":1,"firstPending":true}
{"event":"reaction_like","ts":1736990001000,"userId":2,"targetType":"POST","targetId":90001,"reactionType":"LIKE","action":"ADD","desiredState":1,"delta":1,"currentCount":2,"firstPending":true}
{"event":"reaction_like","ts":1736990002000,"userId":3,"targetType":"POST","targetId":90001,"reactionType":"LIKE","action":"ADD","desiredState":1,"delta":1,"currentCount":3,"firstPending":true}
```

### A4) 验收（你要看到什么）

- 消费 `topic_like_5m_agg` 能看到聚合结果（like_add_count 会增加）
- （可选）消费 `topic_like_hot_alert` 能看到超过阈值的热点事件

---

## B. 用 Logstash 从应用日志喂 Kafka（你自己配置，不改 Java）

> 前提：你的应用日志里能打印出一整段 JSON（作为 %msg）。

### B1) 配置与启动

使用模板：`logstash/topic_like_monitor.conf`

你需要改 3 个地方：

1) `path`：改成你的应用日志路径（例如 `logs/nexus.log`）
2) `sincedb_path`：Windows 可用 `NUL`；Linux 可用 `/dev/null`（为了测试时每次都从头读）
3) `bootstrap_servers`：改成你的 Kafka 地址

### B2) 验收

- 你在应用里触发一次点赞（或手工写一条符合格式的日志行）
- Kafka `topic_like_monitor` 能收到 JSON 事件

---

## C. Hive / Spark（离线分析，占位流程）

- Hive DDL：`hive/dwd_like_monitor_hourly.sql`
- Spark SQL：`spark/ads_like_hot_daily.sql`

验收：

- Hive 表能建出来（按 dt/hour 分区）
- Spark SQL 跑完能得到昨日 TopN 热点

---

## D. Prometheus/Grafana（可选，占位流程）

`prometheus/prometheus.yml` 只放了占位，你要按你的环境接：

- Flink metrics endpoint
- Kafka exporter（如果你用）
- Redis exporter（如果你用）
