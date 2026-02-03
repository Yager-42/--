-- 用户域事件 Outbox（昵称变更等）
CREATE TABLE IF NOT EXISTS `user_event_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(64) NOT NULL COMMENT '例如: user.nickname_changed',
  `fingerprint` VARCHAR(128) NOT NULL COMMENT '去重键（建议: event_type:userId:tsMs）',
  `payload` TEXT NOT NULL COMMENT 'JSON',
  `status` VARCHAR(16) NOT NULL COMMENT 'NEW/DONE/FAIL',
  `retry_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_event_outbox_fingerprint` (`fingerprint`),
  KEY `idx_user_event_outbox_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户域事件 Outbox（昵称变更等）';

