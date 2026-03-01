-- 1.12 下游一致性：内容域事件 Outbox（发布/更新/删除）

CREATE TABLE `content_event_outbox` (
  `event_id` VARCHAR(128) NOT NULL COMMENT '事件唯一指纹（幂等键）',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型（post.published/updated/deleted）',
  `payload_json` LONGTEXT NOT NULL COMMENT '事件载荷 JSON',
  `status` VARCHAR(16) NOT NULL COMMENT 'NEW/SENT/FAIL',
  `retry_count` INT DEFAULT 0 COMMENT '重试次数',
  `next_retry_time` DATETIME DEFAULT NULL COMMENT '下次重试时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  INDEX `idx_status_next_retry` (`status`, `next_retry_time`),
  INDEX `idx_update_time` (`update_time`)
) ENGINE=InnoDB COMMENT='内容域事件 outbox（确保写库成功后事件可重试投递）';

