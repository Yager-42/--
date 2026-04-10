-- Nexus final MySQL schema
-- Derived from current code paths under project/nexus on 2026-03-22.
-- Old SQL files remain for history; this file is the single bootstrap target.
--
-- Not included on purpose:
-- 1) Doc-only tables not used by current code: community_group, user_relation_group,
--    user_relation_group_member, analytics tables.
-- 2) Post/comment body KV tables are not in MySQL now.
--    Runtime code uses Cassandra:
--      - nexus_kv.note_content
--      - nexus_kv.comment_content
--    So this file does not create post_content / comment_content.
--
-- Optional:
-- CREATE DATABASE IF NOT EXISTS `nexus_social` DEFAULT CHARACTER SET utf8mb4;
-- USE `nexus_social`;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user_base` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `username` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '区分大小写的唯一用户名',
  `nickname` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '展示昵称',
  `avatar_url` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像URL',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础信息表';

CREATE TABLE IF NOT EXISTS `user_status` (
  `user_id` BIGINT NOT NULL,
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/DEACTIVATED',
  `deactivated_time` DATETIME NULL,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户状态表';

CREATE TABLE IF NOT EXISTS `user_privacy_setting` (
  `user_id` BIGINT NOT NULL,
  `need_approval` TINYINT NOT NULL DEFAULT 0 COMMENT '1需要审批,0不需要',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户隐私配置';

CREATE TABLE IF NOT EXISTS `user_event_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(64) NOT NULL COMMENT '例如 user.nickname_changed',
  `fingerprint` VARCHAR(128) NOT NULL COMMENT '幂等去重键',
  `payload` TEXT NOT NULL COMMENT '事件JSON',
  `status` VARCHAR(16) NOT NULL COMMENT 'NEW/DONE/FAIL',
  `retry_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_event_outbox_fingerprint` (`fingerprint`),
  KEY `idx_user_event_outbox_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户域事件Outbox';

CREATE TABLE IF NOT EXISTS `auth_account` (
  `account_id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `phone` VARCHAR(32) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `password_updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_login_at` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uk_auth_account_user_id` (`user_id`),
  UNIQUE KEY `uk_auth_account_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认证账号表';

CREATE TABLE IF NOT EXISTS `auth_sms_code` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `biz_type` VARCHAR(32) NOT NULL,
  `phone` VARCHAR(32) NOT NULL,
  `code_hash` VARCHAR(255) NOT NULL,
  `expire_at` DATETIME NOT NULL,
  `used_at` DATETIME NULL,
  `verify_fail_count` INT NOT NULL DEFAULT 0,
  `send_status` VARCHAR(32) NOT NULL DEFAULT 'SENT',
  `request_ip` VARCHAR(64) NOT NULL DEFAULT '',
  `latest_flag` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_auth_sms_code_latest` (`phone`, `biz_type`, `latest_flag`, `used_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码表';

CREATE TABLE IF NOT EXISTS `auth_role` (
  `role_id` BIGINT NOT NULL,
  `role_code` VARCHAR(64) NOT NULL,
  `role_name` VARCHAR(128) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_auth_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认证角色表';

CREATE TABLE IF NOT EXISTS `auth_user_role` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auth_user_role_user_role` (`user_id`, `role_id`),
  KEY `idx_auth_user_role_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系表';

CREATE TABLE IF NOT EXISTS `leaf_alloc` (
  `biz_tag` VARCHAR(128) NOT NULL COMMENT '业务标识',
  `max_id` BIGINT NOT NULL DEFAULT 0,
  `step` INT NOT NULL DEFAULT 1000,
  `description` VARCHAR(256) NOT NULL DEFAULT '',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Leaf号段表';

CREATE TABLE IF NOT EXISTS `user_relation` (
  `id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL COMMENT '发起方ID',
  `target_id` BIGINT NOT NULL COMMENT '目标方ID',
  `relation_type` TINYINT NOT NULL COMMENT '1关注,3屏蔽',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1正常',
  `group_id` BIGINT NOT NULL DEFAULT 0,
  `version` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_target_type` (`source_id`, `target_id`, `relation_type`),
  KEY `idx_source_type_status_time_target` (`source_id`, `relation_type`, `status`, `create_time`, `target_id`),
  KEY `idx_target_type_status_time_source` (`target_id`, `relation_type`, `status`, `create_time`, `source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关系正向表';

CREATE TABLE IF NOT EXISTS `user_follower` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL COMMENT '被关注者ID',
  `follower_id` BIGINT NOT NULL COMMENT '粉丝ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_follower` (`user_id`, `follower_id`),
  KEY `idx_user_time_follower` (`user_id`, `create_time`, `follower_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户粉丝反向表';

CREATE TABLE IF NOT EXISTS `relation_event_outbox` (
  `event_id` BIGINT NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'NEW',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_relation_outbox_status_retry` (`status`, `next_retry_time`),
  KEY `idx_relation_outbox_status_update_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件Outbox';

CREATE TABLE IF NOT EXISTS `relation_event_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `event_type` VARCHAR(32) NOT NULL,
  `fingerprint` VARCHAR(128) NOT NULL,
  `payload` TEXT NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'NEW',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fingerprint` (`fingerprint`),
  KEY `idx_relation_inbox_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件收件箱';

CREATE TABLE IF NOT EXISTS `content_draft` (
  `draft_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(255) NULL,
  `draft_content` LONGTEXT NULL,
  `media_ids` TEXT NULL COMMENT '逗号串或JSON字符串',
  `device_id` VARCHAR(64) NULL,
  `client_version` BIGINT NULL,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`draft_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端草稿箱';

CREATE TABLE IF NOT EXISTS `content_post` (
  `post_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(255) NOT NULL COMMENT '发布标题',
  `content_uuid` CHAR(36) NOT NULL COMMENT '正文UUID，正文实际存 Cassandra note_content',
  `summary` TEXT NULL COMMENT '摘要',
  `summary_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未生成,1已生成,2失败',
  `media_type` TINYINT NOT NULL DEFAULT 0 COMMENT '0纯文,1图文,2视频',
  `media_info` LONGTEXT NULL COMMENT '媒体信息原始字符串',
  `location_info` TEXT NULL COMMENT '位置信息原始字符串',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '0草稿保留,1待审核,2已发布,3已拒绝,6已删除',
  `visibility` TINYINT NOT NULL DEFAULT 0 COMMENT '0公开,1好友,2仅自己',
  `version_num` INT NOT NULL DEFAULT 1,
  `is_edited` TINYINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `publish_time` DATETIME NULL COMMENT '实际发布时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `delete_time` DATETIME NULL COMMENT '软删时间',
  PRIMARY KEY (`post_id`),
  KEY `idx_post_user_status_time` (`user_id`, `status`, `create_time`, `post_id`),
  KEY `idx_post_status_time` (`status`, `create_time`, `post_id`),
  KEY `idx_post_status_visibility_publish` (`status`, `visibility`, `publish_time`, `post_id`),
  KEY `idx_post_status_delete_time` (`status`, `delete_time`, `post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容主表';

CREATE TABLE IF NOT EXISTS `content_post_type` (
  `post_id` BIGINT NOT NULL,
  `type` VARCHAR(64) NOT NULL COMMENT '业务标签/类型',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子类型映射表';

CREATE TABLE IF NOT EXISTS `content_history` (
  `history_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL,
  `version_num` INT NOT NULL,
  `snapshot_title` VARCHAR(255) NOT NULL,
  `snapshot_content` LONGTEXT NULL,
  `snapshot_media` LONGTEXT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`history_id`),
  UNIQUE KEY `uk_post_version` (`post_id`, `version_num`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容版本历史表';

CREATE TABLE IF NOT EXISTS `content_publish_attempt` (
  `attempt_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `idempotent_token` VARCHAR(128) NOT NULL,
  `transcode_job_id` VARCHAR(128) NULL,
  `attempt_status` TINYINT NOT NULL COMMENT '0创建,1风控拒绝,2转码中,3可发布保留,4已发布,5失败,6取消,7待审核',
  `risk_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未评估,1通过,2拒绝,3待审核',
  `transcode_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始,1处理中,2完成,3失败',
  `snapshot_title` VARCHAR(255) NOT NULL,
  `snapshot_content` LONGTEXT NULL,
  `snapshot_media` LONGTEXT NULL,
  `location_info` TEXT NULL,
  `visibility` TINYINT NOT NULL DEFAULT 0,
  `published_version_num` INT NULL,
  `error_code` VARCHAR(64) NULL,
  `error_message` TEXT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`attempt_id`),
  UNIQUE KEY `uk_content_publish_attempt_token` (`idempotent_token`),
  KEY `idx_content_publish_attempt_post_user_status` (`post_id`, `user_id`, `attempt_status`, `attempt_id`),
  KEY `idx_content_publish_attempt_transcode_job` (`transcode_job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布尝试过程表';

CREATE TABLE IF NOT EXISTS `content_schedule` (
  `task_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL COMMENT '绑定的postId，约定等于draftId',
  `content_data` LONGTEXT NULL COMMENT '任务快照，当前仅保留字段',
  `schedule_time` DATETIME NOT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待执行,1保留,2已发布,3取消/失败',
  `retry_count` INT NOT NULL DEFAULT 0,
  `idempotent_token` VARCHAR(128) NULL,
  `is_canceled` TINYINT NOT NULL DEFAULT 0,
  `last_error` TEXT NULL,
  `alarm_sent` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `uk_content_schedule_token` (`idempotent_token`),
  KEY `idx_content_schedule_status_time` (`status`, `schedule_time`),
  KEY `idx_content_schedule_post_status_cancel_time` (`post_id`, `status`, `is_canceled`, `schedule_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时发布任务表';

CREATE TABLE IF NOT EXISTS `content_event_outbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `payload_json` LONGTEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL COMMENT 'NEW/SENT/FAIL',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_content_outbox_status_retry` (`status`, `next_retry_time`),
  KEY `idx_content_outbox_status_update_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容域事件Outbox';

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

CREATE TABLE IF NOT EXISTS `interaction_notify_inbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NULL,
  `status` VARCHAR(16) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事件收件箱';

CREATE TABLE IF NOT EXISTS `interaction_comment_inbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论事件收件箱';

CREATE TABLE IF NOT EXISTS `interaction_comment` (
  `comment_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `root_id` BIGINT NULL,
  `parent_id` BIGINT NULL,
  `reply_to_id` BIGINT NULL,
  `content_id` CHAR(36) NOT NULL COMMENT '评论正文UUID，正文实际存 Cassandra comment_content',
  `status` TINYINT NOT NULL COMMENT '0待审核,1正常,2删除',
  `like_count` BIGINT NOT NULL DEFAULT 0,
  `reply_count` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`comment_id`),
  KEY `idx_comment_post_root_status_time` (`post_id`, `root_id`, `status`, `create_time`, `comment_id`),
  KEY `idx_comment_root_status_time` (`root_id`, `status`, `create_time`, `comment_id`),
  KEY `idx_comment_status_update_time` (`status`, `update_time`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动评论表';

CREATE TABLE IF NOT EXISTS `interaction_comment_pin` (
  `post_id` BIGINT NOT NULL,
  `comment_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论置顶表';

CREATE TABLE IF NOT EXISTS `interaction_notification` (
  `notification_id` BIGINT NOT NULL,
  `to_user_id` BIGINT NOT NULL,
  `biz_type` VARCHAR(32) NOT NULL,
  `target_type` VARCHAR(16) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `post_id` BIGINT NULL,
  `root_comment_id` BIGINT NULL,
  `last_actor_user_id` BIGINT NULL,
  `last_comment_id` BIGINT NULL,
  `unread_count` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uk_user_biz_target` (`to_user_id`, `biz_type`, `target_type`, `target_id`),
  KEY `idx_notification_user_time_id` (`to_user_id`, `update_time`, `notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知聚合表';

CREATE TABLE IF NOT EXISTS `risk_decision_log` (
  `decision_id` BIGINT NOT NULL,
  `event_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `action_type` VARCHAR(32) NOT NULL,
  `scenario` VARCHAR(64) NOT NULL,
  `result` VARCHAR(16) NOT NULL,
  `reason_code` VARCHAR(64) NOT NULL DEFAULT '',
  `request_hash` VARCHAR(64) NOT NULL,
  `signals_json` LONGTEXT NULL,
  `actions_json` TEXT NULL,
  `ext_json` TEXT NULL,
  `trace_id` VARCHAR(64) NOT NULL DEFAULT '',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`decision_id`),
  UNIQUE KEY `uk_user_event` (`user_id`, `event_id`),
  KEY `idx_risk_decision_event_id` (`event_id`),
  KEY `idx_risk_decision_user_time` (`user_id`, `create_time`, `decision_id`),
  KEY `idx_risk_decision_result_time` (`result`, `create_time`, `decision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控决策审计日志';

CREATE TABLE IF NOT EXISTS `risk_case` (
  `case_id` BIGINT NOT NULL,
  `decision_id` BIGINT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `queue` VARCHAR(32) NOT NULL DEFAULT 'default',
  `assignee` BIGINT NULL,
  `result` VARCHAR(16) NOT NULL DEFAULT '',
  `evidence_json` TEXT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`case_id`),
  UNIQUE KEY `uk_decision` (`decision_id`),
  KEY `idx_risk_case_status_queue_time` (`status`, `queue`, `update_time`, `case_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控人审工单';

CREATE TABLE IF NOT EXISTS `risk_rule_version` (
  `version` BIGINT NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `rules_json` LONGTEXT NOT NULL,
  `create_by` BIGINT NULL,
  `publish_by` BIGINT NULL,
  `publish_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`version`),
  KEY `idx_risk_rule_status_publish_time` (`status`, `publish_time`, `version`),
  KEY `idx_risk_rule_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则版本';

CREATE TABLE IF NOT EXISTS `risk_prompt_version` (
  `version` BIGINT NOT NULL,
  `content_type` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `prompt_text` LONGTEXT NOT NULL,
  `model` VARCHAR(64) NOT NULL DEFAULT '',
  `create_by` BIGINT NULL,
  `publish_by` BIGINT NULL,
  `publish_time` DATETIME NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`version`),
  KEY `idx_risk_prompt_type_status_publish_time` (`content_type`, `status`, `publish_time`, `version`),
  KEY `idx_risk_prompt_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控Prompt版本';

CREATE TABLE IF NOT EXISTS `risk_punishment` (
  `punish_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `reason_code` VARCHAR(64) NOT NULL DEFAULT '',
  `decision_id` BIGINT NULL,
  `operator_id` BIGINT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`punish_id`),
  UNIQUE KEY `uk_decision_type` (`decision_id`, `type`),
  KEY `idx_risk_punishment_user_status_time` (`user_id`, `status`, `end_time`, `punish_id`),
  KEY `idx_risk_punishment_user_create_time` (`user_id`, `create_time`, `punish_id`),
  KEY `idx_risk_punishment_decision_id` (`decision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控处罚事实表';

CREATE TABLE IF NOT EXISTS `risk_feedback` (
  `feedback_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `decision_id` BIGINT NULL,
  `punish_id` BIGINT NULL,
  `content` TEXT NULL,
  `result` VARCHAR(16) NOT NULL DEFAULT '',
  `operator_id` BIGINT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`feedback_id`),
  KEY `idx_risk_feedback_user_time` (`user_id`, `create_time`, `feedback_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控反馈与申诉';

CREATE TABLE IF NOT EXISTS `reliable_mq_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(128) NOT NULL,
  `exchange_name` VARCHAR(128) NOT NULL,
  `routing_key` VARCHAR(128) NOT NULL,
  `payload_type` VARCHAR(255) NOT NULL,
  `payload_json` TEXT NOT NULL,
  `headers_json` TEXT NULL,
  `status` VARCHAR(32) NOT NULL,
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME NOT NULL,
  `last_error` VARCHAR(512) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reliable_mq_outbox_event_id` (`event_id`),
  KEY `idx_reliable_mq_outbox_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用RabbitMQ Outbox';

CREATE TABLE IF NOT EXISTS `reliable_mq_replay_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(128) NOT NULL,
  `consumer_name` VARCHAR(128) NOT NULL,
  `original_queue` VARCHAR(128) NOT NULL,
  `original_exchange` VARCHAR(128) NOT NULL,
  `original_routing_key` VARCHAR(128) NOT NULL,
  `payload_type` VARCHAR(255) NOT NULL,
  `payload_json` TEXT NOT NULL,
  `status` VARCHAR(32) NOT NULL,
  `attempt` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME NOT NULL,
  `last_error` VARCHAR(512) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reliable_mq_replay_consumer_event` (`event_id`, `consumer_name`),
  KEY `idx_reliable_mq_replay_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='失败消息重放记录';

CREATE TABLE IF NOT EXISTS `reliable_mq_consumer_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(128) NOT NULL,
  `consumer_name` VARCHAR(128) NOT NULL,
  `payload_json` TEXT NULL,
  `status` VARCHAR(32) NOT NULL,
  `last_error` VARCHAR(512) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reliable_mq_consumer_event` (`event_id`, `consumer_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消费幂等记录';

INSERT INTO `auth_role` (`role_id`, `role_code`, `role_name`)
VALUES
  (1, 'USER', '普通用户'),
  (2, 'ADMIN', '管理员')
ON DUPLICATE KEY UPDATE
  `role_name` = VALUES(`role_name`);
