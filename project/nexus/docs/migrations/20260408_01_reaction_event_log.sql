CREATE TABLE IF NOT EXISTS `interaction_reaction_event_log` (
  `seq` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(128) NOT NULL,
  `target_type` VARCHAR(32) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `reaction_type` VARCHAR(16) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `desired_state` TINYINT NOT NULL,
  `delta` TINYINT NOT NULL,
  `event_time` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`seq`),
  UNIQUE KEY `uk_interaction_reaction_event_log_event_id` (`event_id`),
  KEY `idx_reaction_event_log_target_seq` (`target_type`, `target_id`, `reaction_type`, `seq`),
  KEY `idx_reaction_event_log_user_seq` (`user_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动态势事件流水表';
