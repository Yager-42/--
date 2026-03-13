-- Flink SQL：点赞事件 5 分钟窗口聚合（不写 Java）
-- 你必须先把 KAFKA_BOOTSTRAP_SERVERS 替换成真实地址。

-- 1) Kafka 输入：topic_like_monitor
CREATE TABLE like_events (
  event STRING,
  ts BIGINT,
  userId BIGINT,
  targetType STRING,
  targetId BIGINT,
  reactionType STRING,
  action STRING,
  desiredState INT,
  delta INT,
  currentCount BIGINT,
  firstPending BOOLEAN,
  proc_time AS PROCTIME()
) WITH (
  'connector' = 'kafka',
  'topic' = 'topic_like_monitor',
  'properties.bootstrap.servers' = 'KAFKA_BOOTSTRAP_SERVERS',
  'properties.group.id' = 'flink_like_monitor',
  'scan.startup.mode' = 'earliest-offset',
  'format' = 'json',
  'json.ignore-parse-errors' = 'true'
);

-- 2) Kafka 输出：topic_like_5m_agg
CREATE TABLE like_5m_agg (
  window_start TIMESTAMP(3),
  window_end TIMESTAMP(3),
  targetType STRING,
  targetId BIGINT,
  reactionType STRING,
  like_add_count BIGINT
) WITH (
  'connector' = 'kafka',
  'topic' = 'topic_like_5m_agg',
  'properties.bootstrap.servers' = 'KAFKA_BOOTSTRAP_SERVERS',
  'format' = 'json'
);

-- 3) 5 分钟窗口聚合（只统计 delta=+1 的新增点赞）
INSERT INTO like_5m_agg
SELECT
  window_start,
  window_end,
  targetType,
  targetId,
  reactionType,
  SUM(CASE WHEN delta = 1 THEN 1 ELSE 0 END) AS like_add_count
FROM TABLE(
  TUMBLE(TABLE like_events, DESCRIPTOR(proc_time), INTERVAL '5' MINUTES)
)
WHERE event = 'reaction_like'
GROUP BY window_start, window_end, targetType, targetId, reactionType;

-- 4) （可选）热点告警输出：topic_like_hot_alert
CREATE TABLE like_hot_alert (
  window_end TIMESTAMP(3),
  targetType STRING,
  targetId BIGINT,
  reactionType STRING,
  like_add_count BIGINT,
  threshold BIGINT
) WITH (
  'connector' = 'kafka',
  'topic' = 'topic_like_hot_alert',
  'properties.bootstrap.servers' = 'KAFKA_BOOTSTRAP_SERVERS',
  'format' = 'json'
);

INSERT INTO like_hot_alert
SELECT
  window_end,
  targetType,
  targetId,
  reactionType,
  SUM(CASE WHEN delta = 1 THEN 1 ELSE 0 END) AS like_add_count,
  2000 AS threshold
FROM TABLE(
  TUMBLE(TABLE like_events, DESCRIPTOR(proc_time), INTERVAL '5' MINUTES)
)
WHERE event = 'reaction_like'
GROUP BY window_end, targetType, targetId, reactionType
HAVING SUM(CASE WHEN delta = 1 THEN 1 ELSE 0 END) > 2000;
