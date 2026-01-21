-- 社交领域数据库（基于《社交领域数据库.md》，结合当前实现的轻微补充）

-- 用户基础表：用于评论/通知读侧补全 nickname/avatar，以及 @username -> userId 映射
CREATE TABLE IF NOT EXISTS `user_base` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID (Sharding Key)',
  `username` VARCHAR(64) NOT NULL COMMENT '全局唯一用户名（用于 @username 提及）',
  `avatar_url` VARCHAR(255) DEFAULT '' COMMENT '头像URL',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础信息表';

-- 用户关系表（正向：我关注/好友/屏蔽谁）
CREATE TABLE IF NOT EXISTS `user_relation` (
  `id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL COMMENT '发起方ID (Sharding Key)',
  `target_id` BIGINT NOT NULL COMMENT '目标方ID',
  `relation_type` TINYINT NOT NULL COMMENT '1关注，2好友，3屏蔽',
  `status` TINYINT DEFAULT 1 COMMENT '1正常/通过，2待审批，3已拒绝',
  `group_id` BIGINT DEFAULT 0 COMMENT '分组ID（关注分组）',
  `version` BIGINT DEFAULT 0 COMMENT '乐观锁',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_target_type` (`source_id`, `target_id`, `relation_type`),
  KEY `idx_source_status` (`source_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关系正向表';

-- 粉丝表（反向：谁关注了我）- 保持文档设计，当前实现未直接写入，可用于后续 fanout
CREATE TABLE IF NOT EXISTS `user_follower` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL COMMENT '被关注者ID (Sharding Key)',
  `follower_id` BIGINT NOT NULL COMMENT '粉丝ID',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_follower` (`user_id`, `follower_id`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户粉丝反向表';

-- 好友请求表（待审批/已处理）
CREATE TABLE IF NOT EXISTS `friend_request` (
  `request_id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL,
  `target_id` BIGINT NOT NULL,
  `idempotent_key` VARCHAR(64) NOT NULL,
  `status` TINYINT DEFAULT 2 COMMENT '1通过，2待审批，3已拒绝',
  `version` BIGINT DEFAULT 0 COMMENT '乐观锁',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`request_id`),
  UNIQUE KEY `uk_idem` (`idempotent_key`),
  KEY `idx_target_status` (`target_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友请求';

-- 关注分组表（列表管理）
CREATE TABLE IF NOT EXISTS `user_relation_group` (
  `group_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `group_name` VARCHAR(128) NOT NULL,
  `is_deleted` TINYINT DEFAULT 0,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`group_id`),
  UNIQUE KEY `uk_user_group` (`user_id`, `group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注分组';

-- 关注分组成员表（分组-成员映射）
CREATE TABLE IF NOT EXISTS `user_relation_group_member` (
  `id` BIGINT NOT NULL,
  `group_id` BIGINT NOT NULL,
  `member_id` BIGINT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_member` (`group_id`, `member_id`),
  KEY `idx_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注分组成员映射';

-- 用户隐私配置表
CREATE TABLE IF NOT EXISTS `user_privacy_setting` (
  `user_id` BIGINT NOT NULL,
  `need_approval` TINYINT DEFAULT 0 COMMENT '1需要审批，0公开',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户隐私配置';

-- 关系事件收件箱（MQ 幂等）
CREATE TABLE IF NOT EXISTS `relation_event_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型 FOLLOW/FRIEND/BLOCK',
  `fingerprint` VARCHAR(128) NOT NULL COMMENT '事件指纹，唯一去重',
  `payload` TEXT NOT NULL COMMENT '事件内容序列化',
  `status` VARCHAR(32) NOT NULL DEFAULT 'NEW' COMMENT '状态 NEW/PROCESSED/FAILED',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fingerprint` (`fingerprint`),
  KEY `idx_event_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件收件箱';


-- 互动-点赞/态势（Reaction）事实表：用户是否对目标表达过某种态势
CREATE TABLE IF NOT EXISTS `interaction_reaction` (
  `target_type` VARCHAR(32) NOT NULL COMMENT '目标类型 POST/COMMENT',
  `target_id` BIGINT NOT NULL COMMENT '目标ID (Sharding Key)',
  `reaction_type` VARCHAR(16) NOT NULL COMMENT '态势类型 LIKE/LOVE/ANGRY（当前主要使用 LIKE）',
  `user_id` BIGINT NOT NULL COMMENT '发起用户ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`target_type`, `target_id`, `reaction_type`, `user_id`),
  KEY `idx_user_time` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动态势事实表（真相：某用户是否对某目标做过某态势）';

-- 互动-态势计数（派生表）：某目标某态势的聚合计数
CREATE TABLE IF NOT EXISTS `interaction_reaction_count` (
  `target_type` VARCHAR(32) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `reaction_type` VARCHAR(16) NOT NULL,
  `count` BIGINT NOT NULL DEFAULT 0 COMMENT '聚合计数（最终一致）',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`target_type`, `target_id`, `reaction_type`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动态势计数表（派生值）';

-- 互动-通知（聚合收件箱）：按用户+目标聚合 unread_count，列表按 update_time 倒序
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
  KEY `idx_user_time_id` (`to_user_id`, `update_time`, `notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知收件箱（聚合）';

-- 通知事件收件箱（MQ 幂等去重）：仅插入成功的 event_id 才允许继续执行业务写入
CREATE TABLE IF NOT EXISTS `interaction_notify_inbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NULL,
  `status` VARCHAR(16) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事件收件箱（幂等去重）';
