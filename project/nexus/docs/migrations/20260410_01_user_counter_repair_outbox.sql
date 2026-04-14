CREATE TABLE IF NOT EXISTS `user_counter_repair_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_user_id` BIGINT NOT NULL,
  `target_user_id` BIGINT NOT NULL,
  `operation` VARCHAR(16) NOT NULL,
  `reason` VARCHAR(64) NOT NULL,
  `correlation_id` VARCHAR(128) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'NEW',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_counter_repair_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='user counter repair outbox';
