-- Spark SQL：昨日热点 TopN（占位）
-- 你需要把 ${dt} 替换成目标日期，例如 2026-01-16

CREATE TABLE IF NOT EXISTS ads_like_hot_daily (
  target_type STRING,
  target_id BIGINT,
  reaction_type STRING,
  score BIGINT
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET;

WITH daily AS (
  SELECT
    target_type,
    target_id,
    reaction_type,
    SUM(like_add_count) AS score
  FROM dwd_like_monitor_hourly
  WHERE dt = '${dt}'
  GROUP BY target_type, target_id, reaction_type
), ranked AS (
  SELECT
    target_type,
    target_id,
    reaction_type,
    score,
    ROW_NUMBER() OVER (PARTITION BY target_type ORDER BY score DESC) AS rn
  FROM daily
)
INSERT OVERWRITE TABLE ads_like_hot_daily PARTITION (dt='${dt}')
SELECT target_type, target_id, reaction_type, score
FROM ranked
WHERE rn <= 10;
