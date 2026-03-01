-- 1.10 delete 并发：content_post 增加 update_time/delete_time（用于后续定时清理）

ALTER TABLE content_post
    ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD COLUMN delete_time DATETIME DEFAULT NULL COMMENT '删除时间（软删）';

