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

CREATE TABLE `content_post_type` (
  `post_id` BIGINT NOT NULL COMMENT '内容ID',
  `type` VARCHAR(64) NOT NULL COMMENT '业务类型/主题（用户发布时提交）',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`, `type`),
  INDEX `idx_type` (`type`)
) ENGINE=InnoDB COMMENT='内容-业务类型映射表（一对多）';

CREATE TABLE `content_history` (
  `history_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL COMMENT '关联内容ID',
  `version_num` INT NOT NULL COMMENT '版本号',
  `snapshot_content` TEXT COMMENT '内容快照',
  `snapshot_media` JSON COMMENT '媒体快照',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`history_id`),
  UNIQUE KEY `uk_post_ver` (`post_id`, `version_num`)
) ENGINE=InnoDB COMMENT='内容版本历史表（全量快照）';

CREATE TABLE `content_publish_attempt` (
  `attempt_id` BIGINT NOT NULL COMMENT '尝试ID',
  `post_id` BIGINT NOT NULL COMMENT '目标内容ID',
  `user_id` BIGINT NOT NULL COMMENT '发起用户',
  `idempotent_token` VARCHAR(128) NOT NULL COMMENT '幂等键',
  `transcode_job_id` VARCHAR(128) DEFAULT NULL COMMENT '转码任务ID',
  `attempt_status` TINYINT NOT NULL COMMENT '0创建,1风控拒绝,2转码中,3可发布,4已发布,5失败,6取消',
  `risk_status` TINYINT DEFAULT 0 COMMENT '0未评估,1通过,2拒绝',
  `transcode_status` TINYINT DEFAULT 0 COMMENT '0未开始,1处理中,2完成,3失败',
  `snapshot_content` TEXT COMMENT '文本快照',
  `snapshot_media` JSON COMMENT '媒体快照',
  `location_info` JSON COMMENT '位置快照',
  `visibility` TINYINT DEFAULT 0 COMMENT '0公开,1好友,2仅自己',
  `published_version_num` INT DEFAULT NULL COMMENT '成功发布的可见版本号',
  `error_code` VARCHAR(64) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`attempt_id`),
  UNIQUE KEY `uk_idempotent_token` (`idempotent_token`),
  INDEX `idx_post_time` (`post_id`, `create_time`),
  INDEX `idx_user_time` (`user_id`, `create_time`),
  INDEX `idx_transcode_job` (`transcode_job_id`)
) ENGINE=InnoDB COMMENT='内容发布尝试过程表';

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

-- 定时发布死信队列
-- 依赖 RabbitMQ x-delayed-message 插件，DLX 配置在代码中

-- 如需增加幂等 token / 取消标记，可在 content_schedule 表增加：
--   idempotent_token VARCHAR(128), is_canceled TINYINT DEFAULT 0

-- 建议扩展字段以支持取消/变更与告警：
--   last_error TEXT, alarm_sent TINYINT DEFAULT 0
