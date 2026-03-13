-- 1.9 定时发布：绑定 postId（=draftId），并迁移旧数据

ALTER TABLE content_schedule
    ADD COLUMN post_id BIGINT NOT NULL DEFAULT 0 COMMENT '绑定的 postId（=draftId）';

CREATE INDEX idx_content_schedule_post_status
    ON content_schedule(post_id, status);

-- 迁移：旧数据没有 postId，直接取消（否则无法保证“不意外多发”）
UPDATE content_schedule
SET status = 3,
    is_canceled = 1,
    last_error = 'schema_migration: legacy schedule without post_id'
WHERE status = 0 AND post_id = 0;

