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
  KEY `idx_auth_user_role_user_id` (`user_id`),
  KEY `idx_auth_user_role_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关系表';

INSERT INTO `auth_role` (`role_id`, `role_code`, `role_name`)
VALUES
  (1, 'USER', '普通用户'),
  (2, 'ADMIN', '管理员')
ON DUPLICATE KEY UPDATE
  `role_name` = VALUES(`role_name`);
