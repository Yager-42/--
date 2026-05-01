DROP TABLE IF EXISTS post_counter_projection;
DROP TABLE IF EXISTS user_counter_repair_outbox;
DROP TABLE IF EXISTS interaction_reaction_event_log;

SET @drop_interaction_comment_like_count = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE interaction_comment DROP COLUMN like_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'interaction_comment'
      AND column_name = 'like_count'
);
PREPARE drop_interaction_comment_like_count_stmt FROM @drop_interaction_comment_like_count;
EXECUTE drop_interaction_comment_like_count_stmt;
DEALLOCATE PREPARE drop_interaction_comment_like_count_stmt;

SET @drop_interaction_comment_reply_count = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE interaction_comment DROP COLUMN reply_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'interaction_comment'
      AND column_name = 'reply_count'
);
PREPARE drop_interaction_comment_reply_count_stmt FROM @drop_interaction_comment_reply_count;
EXECUTE drop_interaction_comment_reply_count_stmt;
DEALLOCATE PREPARE drop_interaction_comment_reply_count_stmt;
