-- xiaohashu playbook: migrate comment.content to KV (MySQL adapter)

-- interaction_comment: remove LONGTEXT content and keep a KV pointer.
ALTER TABLE interaction_comment
  DROP COLUMN content,
  ADD COLUMN content_id CHAR(36) NOT NULL COMMENT '评论正文UUID（KV键）' AFTER reply_to_id;
