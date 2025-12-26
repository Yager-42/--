-- 用户隐私配置表
CREATE TABLE IF NOT EXISTS `user_privacy_setting` (
  `user_id` BIGINT NOT NULL,
  `need_approval` TINYINT DEFAULT 0 COMMENT '1需要审批，0公开',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户隐私配置';
