CREATE TABLE IF NOT EXISTS `auth_account` (
  `account_id` BIGINT NOT NULL,
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
  `id` BIGINT NOT NULL,
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
  KEY `idx_auth_sms_code_lookup` (`phone`, `biz_type`, `latest_flag`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码表';
