-- 社交领域内容/媒体相关表 (提取自《社交领域数据库.md》)

CREATE TABLE `content_post` (
  `post_id` BIGINT NOT NULL COMMENT '内容ID (Sharding Key)',
  `user_id` BIGINT NOT NULL,
  `content_text` TEXT COMMENT '文本内容',
  `media_type` TINYINT DEFAULT 0 COMMENT '类型: 0纯文, 1图文, 2视频',
  `media_info` JSON COMMENT '媒体资源信息',
  `location_info` JSON COMMENT '地理位置信息',
  `status` TINYINT DEFAULT 1 COMMENT '0草稿,1审核中,2已发布,3审核拒绝,4定时,6删除',
  `visibility` TINYINT DEFAULT 0 COMMENT '0公开,1好友,2仅自己',
  `version_num` INT DEFAULT 1 COMMENT '当前版本号',
  `is_edited` TINYINT DEFAULT 0 COMMENT '是否编辑过',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`),
  INDEX `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB COMMENT='内容发布主表';

CREATE TABLE `content_history` (
  `history_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL COMMENT '关联内容ID',
  `version_num` INT NOT NULL COMMENT '版本号',
  `snapshot_content` TEXT COMMENT '内容快照',
  `snapshot_media` JSON COMMENT '媒体快照',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`history_id`),
  INDEX `idx_post_ver` (`post_id`, `version_num`)
) ENGINE=InnoDB COMMENT='内容版本历史表';

CREATE TABLE `content_draft` (
  `draft_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `draft_content` LONGTEXT,
  `media_ids` TEXT COMMENT '草稿媒体标识列表（逗号或JSON）',
  `device_id` VARCHAR(64) COMMENT '最后编辑设备',
  `client_version` BIGINT COMMENT '客户端版本号',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`draft_id`),
  INDEX `idx_user` (`user_id`)
) ENGINE=InnoDB COMMENT='云端草稿箱';

CREATE TABLE `content_schedule` (
  `task_id` BIGINT NOT NULL,
  `user_id` BIGINT,
  `content_data` LONGTEXT COMMENT '待发布内容Payload',
  `schedule_time` DATETIME NOT NULL COMMENT '预定发布时间',
  `status` TINYINT DEFAULT 0 COMMENT '0待执行,1执行中,2已完成,3失败/取消',
  `retry_count` INT DEFAULT 0,
  `idempotent_token` VARCHAR(128) DEFAULT NULL,
  `is_canceled` TINYINT DEFAULT 0,
  `last_error` TEXT,
  `alarm_sent` TINYINT DEFAULT 0,
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_idempotent_token` (`idempotent_token`),
  INDEX `idx_time_status` (`schedule_time`, `status`)
) ENGINE=InnoDB COMMENT='定时发布任务表';

-- 文本基准/补丁存储（Git风格）
CREATE TABLE `content_chunk` (
  `chunk_hash` VARCHAR(128) NOT NULL,
  `chunk_data` LONGBLOB,
  `size` BIGINT,
  `compress_algo` VARCHAR(16) DEFAULT 'gzip',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`chunk_hash`)
) ENGINE=InnoDB COMMENT='文本基准块（gzip压缩的全文或分块）';

CREATE TABLE `content_patch` (
  `patch_hash` VARCHAR(128) NOT NULL,
  `patch_data` LONGBLOB,
  `size` BIGINT,
  `compress_algo` VARCHAR(16) DEFAULT 'gzip',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`patch_hash`)
) ENGINE=InnoDB COMMENT='文本差分补丁（gzip压缩后的 unified diff）';

CREATE TABLE `content_revision` (
  `post_id` BIGINT NOT NULL,
  `version_num` INT NOT NULL,
  `base_version` INT NOT NULL,
  `is_base` TINYINT DEFAULT 0 COMMENT '1=基准全文，0=补丁',
  `patch_hash` VARCHAR(128) DEFAULT NULL,
  `chunk_hash` VARCHAR(128) DEFAULT NULL,
  `request_id` VARCHAR(128) DEFAULT NULL COMMENT '幂等键',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`, `version_num`),
  UNIQUE KEY `uk_post_request` (`post_id`, `request_id`),
  KEY `idx_post_ver` (`post_id`, `version_num`)
) ENGINE=InnoDB COMMENT='文本版本记录（指向基准或补丁）';

-- 定时发布死信队列
-- 依赖 RabbitMQ x-delayed-message 插件，DLX 配置在代码中

-- 如需增加幂等 token / 取消标记，可在 content_schedule 表增加：
--   idempotent_token VARCHAR(128), is_canceled TINYINT DEFAULT 0

-- 建议扩展字段以支持取消/变更与告警：
--   last_error TEXT, alarm_sent TINYINT DEFAULT 0
