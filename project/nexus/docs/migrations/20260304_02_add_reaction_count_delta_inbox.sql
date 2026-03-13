-- xiaohashu playbook: Delta count model idempotency inbox (MySQL)

CREATE TABLE IF NOT EXISTS `interaction_reaction_count_delta_inbox` (
  `event_id` VARCHAR(128) NOT NULL COMMENT '上游 LikeUnlike 事件ID（幂等键）',
  `target_type` VARCHAR(16) NOT NULL COMMENT '目标类型：POST/USER',
  `target_id` BIGINT NOT NULL COMMENT '目标ID',
  `reaction_type` VARCHAR(16) NOT NULL COMMENT '反应类型：LIKE 等',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`, `target_type`, `target_id`, `reaction_type`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计数增量幂等去重表（delta inbox）';
