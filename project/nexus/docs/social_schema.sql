-- 社交领域数据库（基于《社交领域数据库.md》，结合当前实现的轻微补充）

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
