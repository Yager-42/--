-- 1.16 timeline summary：content_post 增加 summary/summary_status

ALTER TABLE content_post
    ADD COLUMN summary TEXT NULL COMMENT 'AI 生成摘要',
    ADD COLUMN summary_status TINYINT NOT NULL DEFAULT 0 COMMENT '摘要生成状态：0未生成/1已生成/2失败';

