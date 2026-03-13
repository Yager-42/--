-- xiaohashu playbook: content_post stores content_uuid only

ALTER TABLE content_post
    ADD COLUMN content_uuid CHAR(36) NOT NULL DEFAULT '' COMMENT '正文UUID（KV键）' AFTER user_id;

ALTER TABLE content_post
    DROP COLUMN content_text;
