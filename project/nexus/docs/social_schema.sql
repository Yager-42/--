-- 社交领域数据库（基于《社交领域数据库.md》，结合当前实现的轻微补充）

-- 用户基础表：用于评论/通知读侧补全 nickname/avatar，以及 @username -> userId 映射
CREATE TABLE IF NOT EXISTS `user_base` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID (Sharding Key)',
  `username` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '全局唯一用户名（用于 @username 提及，区分大小写）',
  `nickname` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '展示昵称（可改）',
  `avatar_url` VARCHAR(255) DEFAULT '' COMMENT '头像URL',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础信息表';

-- 社群/圈子表：社群域真相源（Search 本次不依赖）
CREATE TABLE IF NOT EXISTS `community_group` (
  `group_id` BIGINT NOT NULL COMMENT '圈子ID',
  `owner_id` BIGINT NOT NULL DEFAULT 0 COMMENT '群主用户ID（可选）',
  `name` VARCHAR(128) NOT NULL COMMENT '圈子名称',
  `join_mode` TINYINT DEFAULT 0 COMMENT '0任意,1审核,2邀请（可选）',
  `channel_config` JSON NULL COMMENT '频道配置（可选）',

  `member_count` BIGINT NOT NULL DEFAULT 0 COMMENT '成员数（最终一致即可）',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '软删：0正常,1删除',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`group_id`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社群/圈子表';

-- 用户关系表（正向：我关注/好友/屏蔽谁）
CREATE TABLE IF NOT EXISTS `user_relation` (
  `id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL COMMENT '发起方ID (Sharding Key)',
  `target_id` BIGINT NOT NULL COMMENT '目标方ID',
  `relation_type` TINYINT NOT NULL COMMENT '1关注，3屏蔽',
  `status` TINYINT DEFAULT 1 COMMENT '1正常/通过',
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

-- 关系事件 Outbox（事务内落库，轮询发布 MQ）
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
  KEY `idx_relation_outbox_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件 Outbox';

-- 关系事件收件箱（MQ 幂等）
CREATE TABLE IF NOT EXISTS `relation_event_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型 FOLLOW/BLOCK',
  `fingerprint` VARCHAR(128) NOT NULL COMMENT '事件指纹，唯一去重',
  `payload` TEXT NOT NULL COMMENT '事件内容序列化',
  `status` VARCHAR(32) NOT NULL DEFAULT 'NEW' COMMENT '状态 NEW/PROCESSED/FAILED',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fingerprint` (`fingerprint`),
  KEY `idx_event_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件收件箱';

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

-- 评论事件收件箱（MQ 幂等去重）：用于评论创建等非计数派生链路
CREATE TABLE IF NOT EXISTS `interaction_comment_inbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论事件收件箱（幂等去重）';

-- 互动-评论表：两级盖楼（一级评论 root_id=NULL；回复 root_id=所属一级评论ID）
CREATE TABLE IF NOT EXISTS `interaction_comment` (
  `comment_id` BIGINT NOT NULL COMMENT '评论ID',
  `post_id` BIGINT NOT NULL COMMENT '归属帖子ID',
  `user_id` BIGINT NOT NULL COMMENT '评论作者',
  `root_id` BIGINT NULL COMMENT '一级评论为NULL；回复为所属一级评论ID',
  `parent_id` BIGINT NULL COMMENT '直接回复的评论ID（用于展示/定位）',
  `reply_to_id` BIGINT NULL COMMENT '显示“回复@谁”的目标评论ID（用于展示）',
  `content_id` CHAR(36) NOT NULL COMMENT '评论正文UUID（KV键）',
  `status` TINYINT NOT NULL COMMENT '0待审核；1正常；2删除（软删）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`comment_id`),
  KEY `idx_post_root_time` (`post_id`, `root_id`, `create_time`, `comment_id`),
  KEY `idx_root_time` (`root_id`, `create_time`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动-评论表（两级盖楼）';

-- 互动-评论置顶表：一帖一条置顶（置顶不参与分页，读侧单独返回 pinned）
CREATE TABLE IF NOT EXISTS `interaction_comment_pin` (
  `post_id` BIGINT NOT NULL COMMENT '帖子ID（唯一：一帖一条置顶）',
  `comment_id` BIGINT NOT NULL COMMENT '被置顶的一级评论ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`post_id`),
  KEY `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='互动-评论置顶表（一帖一条）';


-- =========================
-- 风控与信任服务（Risk Control & Trust）
-- =========================

-- 决策审计日志：每次决策必落库，用于追溯“为什么拦/为什么放”
CREATE TABLE IF NOT EXISTS `risk_decision_log` (
  `decision_id` BIGINT NOT NULL COMMENT '决策ID',
  `event_id` VARCHAR(128) NOT NULL COMMENT '业务侧事件ID（幂等键的一部分）',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `action_type` VARCHAR(32) NOT NULL COMMENT '动作类型',
  `scenario` VARCHAR(64) NOT NULL COMMENT '场景',
  `result` VARCHAR(16) NOT NULL COMMENT 'PASS/REVIEW/BLOCK/CHALLENGE/SHADOWBAN/LIMIT',
  `reason_code` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原因码（稳定可统计）',
  `request_hash` VARCHAR(64) NOT NULL COMMENT '请求体hash（幂等一致性校验）',
  `signals_json` LONGTEXT NULL COMMENT '命中信号JSON',
  `actions_json` TEXT NULL COMMENT '要执行动作JSON',
  `ext_json` TEXT NULL COMMENT '扩展字段JSON（例如 attemptId/targetId 等）',
  `trace_id` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '链路追踪ID（可选）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`decision_id`),
  UNIQUE KEY `uk_user_event` (`user_id`, `event_id`),
  KEY `idx_event_id` (`event_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_result_time` (`result`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控决策审计日志';

-- 人审工单：承接 REVIEW 结果
CREATE TABLE IF NOT EXISTS `risk_case` (
  `case_id` BIGINT NOT NULL COMMENT '工单ID',
  `decision_id` BIGINT NOT NULL COMMENT '关联决策ID',
  `status` VARCHAR(16) NOT NULL COMMENT 'OPEN/ASSIGNED/DONE',
  `queue` VARCHAR(32) NOT NULL DEFAULT 'default' COMMENT '队列',
  `assignee` BIGINT NULL COMMENT '审核人',
  `result` VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'PASS/BLOCK（结论）',
  `evidence_json` TEXT NULL COMMENT '证据/上下文JSON',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`case_id`),
  UNIQUE KEY `uk_decision` (`decision_id`),
  KEY `idx_status_queue_time` (`status`, `queue`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控人审工单';

-- 规则版本：灰度/发布/回滚的基础设施
CREATE TABLE IF NOT EXISTS `risk_rule_version` (
  `version` BIGINT NOT NULL COMMENT '规则版本号',
  `status` VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/ROLLED_BACK',
  `rules_json` LONGTEXT NOT NULL COMMENT '规则配置JSON',
  `create_by` BIGINT NULL COMMENT '创建人',
  `publish_by` BIGINT NULL COMMENT '发布人',
  `publish_time` DATETIME NULL COMMENT '发布时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`version`),
  KEY `idx_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则版本';

-- Prompt 版本：用于 LLM 提示词灰度/回滚，并将 promptVersion 写入 decision_log 以便对比效果
CREATE TABLE IF NOT EXISTS `risk_prompt_version` (
  `version` BIGINT NOT NULL COMMENT 'Prompt版本号',
  `content_type` VARCHAR(16) NOT NULL COMMENT 'TEXT/IMAGE',
  `status` VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/ROLLED_BACK',
  `prompt_text` LONGTEXT NOT NULL COMMENT '系统提示词/约束（System Prompt）',
  `model` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '可选：绑定模型名（便于回溯）',
  `create_by` BIGINT NULL COMMENT '创建人',
  `publish_by` BIGINT NULL COMMENT '发布人',
  `publish_time` DATETIME NULL COMMENT '发布时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`version`),
  KEY `idx_type_status_time` (`content_type`, `status`, `create_time`),
  KEY `idx_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控 LLM Prompt 版本';

-- 处罚事实表：以 MySQL 为准，Redis 只是缓存
CREATE TABLE IF NOT EXISTS `risk_punishment` (
  `punish_id` BIGINT NOT NULL COMMENT '处罚ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `type` VARCHAR(32) NOT NULL COMMENT '处罚类型（POST_BAN/COMMENT_BAN/LOGIN_BAN/DM_BAN/LIMIT 等）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED/EXPIRED',
  `start_time` DATETIME NOT NULL COMMENT '生效开始时间',
  `end_time` DATETIME NOT NULL COMMENT '生效结束时间',
  `reason_code` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原因码',
  `decision_id` BIGINT NULL COMMENT '来源决策ID（用于幂等与追溯）',
  `operator_id` BIGINT NULL COMMENT '操作人（后台/系统）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`punish_id`),
  UNIQUE KEY `uk_decision_type` (`decision_id`, `type`),
  KEY `idx_user_status_time` (`user_id`, `status`, `end_time`),
  KEY `idx_decision_id` (`decision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控处罚事实表';

-- 反馈/申诉：用于误杀治理与训练真值沉淀
CREATE TABLE IF NOT EXISTS `risk_feedback` (
  `feedback_id` BIGINT NOT NULL COMMENT '反馈ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `type` VARCHAR(16) NOT NULL COMMENT 'APPEAL/LABEL等',
  `status` VARCHAR(16) NOT NULL COMMENT 'OPEN/DONE',
  `decision_id` BIGINT NULL COMMENT '关联决策ID',
  `punish_id` BIGINT NULL COMMENT '关联处罚ID',
  `content` TEXT NULL COMMENT '用户内容/理由',
  `result` VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'ACCEPT/REJECT 等',
  `operator_id` BIGINT NULL COMMENT '处理人',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`feedback_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控反馈与申诉';
