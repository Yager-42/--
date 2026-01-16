-- Hive DDL：点赞监控小时明细（占位）
-- 分区：dt=yyyy-MM-dd, hour=HH

CREATE TABLE IF NOT EXISTS dwd_like_monitor_hourly (
  target_type STRING,
  target_id BIGINT,
  reaction_type STRING,
  like_add_count BIGINT
)
PARTITIONED BY (
  dt STRING,
  hour STRING
)
STORED AS PARQUET;
