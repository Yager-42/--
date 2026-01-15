-- 点赞/态势（LIKE）相关表
-- 说明：用于“Redis 秒回 + 延迟 flush 落库”的最终一致真值。
--
-- 执行者：Codex（Linus mode）
-- 日期：2026-01-15

CREATE TABLE IF NOT EXISTS `likes` (
  `user_id` BIGINT NOT NULL,
  `target_type` VARCHAR(32) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `status` TINYINT NOT NULL COMMENT '1=liked, 0=unliked',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞明细（覆盖式 upsert 写最终态）';

CREATE TABLE IF NOT EXISTS `like_counts` (
  `target_type` VARCHAR(32) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `like_count` BIGINT NOT NULL DEFAULT 0,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞计数（绝对值，重复 flush 幂等）';

