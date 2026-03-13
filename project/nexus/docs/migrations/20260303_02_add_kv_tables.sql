-- xiaohashu playbook: KV tables (MySQL adapter)

CREATE TABLE IF NOT EXISTS `comment_content` (
  `post_id` BIGINT NOT NULL COMMENT '归属帖子ID',
  `year_month` VARCHAR(7) NOT NULL COMMENT '分区字段: YYYY-MM',
  `content_id` CHAR(36) NOT NULL COMMENT '评论正文UUID',
  `content` LONGTEXT NOT NULL COMMENT '评论正文内容',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`post_id`, `year_month`, `content_id`),
  KEY `idx_post_ym` (`post_id`, `year_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论正文KV（MySQL适配）';
