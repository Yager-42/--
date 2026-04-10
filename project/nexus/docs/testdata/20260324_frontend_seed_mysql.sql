-- frontend seed batch: frontend_seed_20260324_v1
-- target db: nexus_social
-- login account:
--   phone    : 13900000001
--   password : Nexus123!
--   user_id  : 900100001

SET NAMES utf8mb4;
USE `nexus_social`;

START TRANSACTION;

INSERT INTO `user_base`
    (`user_id`, `username`, `nickname`, `avatar_url`, `create_time`, `update_time`)
VALUES
    (900100001, 'seed.frontend.viewer', 'Seed Viewer', 'https://i.pravatar.cc/160?img=11', '2026-03-24 08:10:00', '2026-03-24 08:10:00'),
    (900100002, 'seed.frontend.author.a', 'Author Alpha', 'https://i.pravatar.cc/160?img=12', '2026-03-24 08:11:00', '2026-03-24 08:11:00'),
    (900100003, 'seed.frontend.author.b', 'Author Beta', 'https://i.pravatar.cc/160?img=13', '2026-03-24 08:12:00', '2026-03-24 08:12:00'),
    (900100004, 'seed.frontend.follower', 'Follower Gamma', 'https://i.pravatar.cc/160?img=14', '2026-03-24 08:13:00', '2026-03-24 08:13:00')
ON DUPLICATE KEY UPDATE
    `nickname` = VALUES(`nickname`),
    `avatar_url` = VALUES(`avatar_url`),
    `update_time` = VALUES(`update_time`);

INSERT INTO `user_status`
    (`user_id`, `status`, `deactivated_time`, `update_time`)
VALUES
    (900100001, 'ACTIVE', NULL, '2026-03-24 08:10:00'),
    (900100002, 'ACTIVE', NULL, '2026-03-24 08:11:00'),
    (900100003, 'ACTIVE', NULL, '2026-03-24 08:12:00'),
    (900100004, 'ACTIVE', NULL, '2026-03-24 08:13:00')
ON DUPLICATE KEY UPDATE
    `status` = VALUES(`status`),
    `deactivated_time` = VALUES(`deactivated_time`),
    `update_time` = VALUES(`update_time`);

INSERT INTO `user_privacy_setting`
    (`user_id`, `need_approval`, `update_time`)
VALUES
    (900100001, 0, '2026-03-24 08:10:00'),
    (900100002, 0, '2026-03-24 08:11:00'),
    (900100003, 0, '2026-03-24 08:12:00'),
    (900100004, 0, '2026-03-24 08:13:00')
ON DUPLICATE KEY UPDATE
    `need_approval` = VALUES(`need_approval`),
    `update_time` = VALUES(`update_time`);

INSERT INTO `auth_account`
    (`account_id`, `user_id`, `phone`, `password_hash`, `password_updated_at`, `last_login_at`, `create_time`, `update_time`)
VALUES
    (930100001, 900100001, '13900000001', '69f072bf045cb4abc9cbedc37167a09fd08c89486d17b2a692f9d273c2dcb10a', '2026-03-24 08:10:00', NULL, '2026-03-24 08:10:00', '2026-03-24 08:10:00')
ON DUPLICATE KEY UPDATE
    `phone` = VALUES(`phone`),
    `password_hash` = VALUES(`password_hash`),
    `password_updated_at` = VALUES(`password_updated_at`),
    `update_time` = VALUES(`update_time`);

INSERT INTO `auth_user_role`
    (`id`, `user_id`, `role_id`, `create_time`)
VALUES
    (930100011, 900100001, 1, '2026-03-24 08:10:00')
ON DUPLICATE KEY UPDATE
    `create_time` = VALUES(`create_time`);

INSERT INTO `user_relation`
    (`id`, `source_id`, `target_id`, `relation_type`, `status`, `group_id`, `version`, `create_time`)
VALUES
    (940100001, 900100001, 900100002, 1, 1, 0, 0, '2026-03-24 08:20:00'),
    (940100002, 900100001, 900100003, 1, 1, 0, 0, '2026-03-24 08:21:00'),
    (940100003, 900100004, 900100001, 1, 1, 0, 0, '2026-03-24 08:22:00'),
    (940100004, 900100003, 900100001, 1, 1, 0, 0, '2026-03-24 08:23:00')
ON DUPLICATE KEY UPDATE
    `status` = VALUES(`status`),
    `create_time` = VALUES(`create_time`);

INSERT INTO `user_follower`
    (`id`, `user_id`, `follower_id`, `create_time`)
VALUES
    (941100001, 900100002, 900100001, '2026-03-24 08:20:00'),
    (941100002, 900100003, 900100001, '2026-03-24 08:21:00'),
    (941100003, 900100001, 900100004, '2026-03-24 08:22:00'),
    (941100004, 900100001, 900100003, '2026-03-24 08:23:00')
ON DUPLICATE KEY UPDATE
    `create_time` = VALUES(`create_time`);

INSERT INTO `content_post`
    (`post_id`, `user_id`, `title`, `content_uuid`, `summary`, `summary_status`, `media_type`, `media_info`, `location_info`, `status`, `visibility`, `version_num`, `is_edited`, `create_time`, `publish_time`, `update_time`, `delete_time`)
VALUES
    (910100001, 900100002, 'Frontend seed post alpha', '11111111-1111-4111-8111-111111111111', 'Alpha summary for feed and detail smoke tests.', 1, 1, '[{\"url\":\"https://picsum.photos/seed/nexus-alpha/720/1280\"}]', '{\"city\":\"Shanghai\"}', 2, 0, 1, 0, '2026-03-24 08:30:00', '2026-03-24 08:30:00', '2026-03-24 08:30:00', NULL),
    (910100002, 900100003, 'Frontend seed post beta', '22222222-2222-4222-8222-222222222222', 'Beta summary for relation and notification smoke tests.', 1, 1, '[{\"url\":\"https://picsum.photos/seed/nexus-beta/720/1280\"}]', '{\"city\":\"Hangzhou\"}', 2, 0, 1, 0, '2026-03-24 08:35:00', '2026-03-24 08:35:00', '2026-03-24 08:35:00', NULL)
ON DUPLICATE KEY UPDATE
    `title` = VALUES(`title`),
    `content_uuid` = VALUES(`content_uuid`),
    `summary` = VALUES(`summary`),
    `summary_status` = VALUES(`summary_status`),
    `media_type` = VALUES(`media_type`),
    `media_info` = VALUES(`media_info`),
    `location_info` = VALUES(`location_info`),
    `status` = VALUES(`status`),
    `visibility` = VALUES(`visibility`),
    `version_num` = VALUES(`version_num`),
    `is_edited` = VALUES(`is_edited`),
    `publish_time` = VALUES(`publish_time`),
    `update_time` = VALUES(`update_time`),
    `delete_time` = VALUES(`delete_time`);

INSERT INTO `content_post_type`
    (`post_id`, `type`, `create_time`)
VALUES
    (910100001, 'LIFESTYLE', '2026-03-24 08:30:00'),
    (910100002, 'TRAVEL', '2026-03-24 08:35:00')
ON DUPLICATE KEY UPDATE
    `create_time` = VALUES(`create_time`);

INSERT INTO `interaction_comment`
    (`comment_id`, `post_id`, `user_id`, `root_id`, `parent_id`, `reply_to_id`, `content_id`, `status`, `like_count`, `reply_count`, `create_time`, `update_time`)
VALUES
    (920100001, 910100001, 900100001, NULL, NULL, NULL, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1', 1, 2, 1, '2026-03-24 08:40:00', '2026-03-24 08:40:00'),
    (920100002, 910100001, 900100003, NULL, NULL, NULL, 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2', 1, 0, 0, '2026-03-24 08:41:00', '2026-03-24 08:41:00'),
    (920100003, 910100001, 900100002, 920100001, 920100001, 920100001, 'cccccccc-cccc-4ccc-8ccc-ccccccccccc3', 1, 0, 0, '2026-03-24 08:42:00', '2026-03-24 08:42:00')
ON DUPLICATE KEY UPDATE
    `status` = VALUES(`status`),
    `like_count` = VALUES(`like_count`),
    `reply_count` = VALUES(`reply_count`),
    `update_time` = VALUES(`update_time`);

INSERT INTO `interaction_comment_pin`
    (`post_id`, `comment_id`, `create_time`, `update_time`)
VALUES
    (910100001, 920100001, '2026-03-24 08:43:00', '2026-03-24 08:43:00')
ON DUPLICATE KEY UPDATE
    `comment_id` = VALUES(`comment_id`),
    `update_time` = VALUES(`update_time`);


INSERT INTO `interaction_notification`
    (`notification_id`, `to_user_id`, `biz_type`, `target_type`, `target_id`, `post_id`, `root_comment_id`, `last_actor_user_id`, `last_comment_id`, `unread_count`, `create_time`, `update_time`)
VALUES
    (950100001, 900100001, 'POST_LIKED', 'POST', 910100001, 910100001, NULL, 900100004, NULL, 3, '2026-03-24 08:56:00', '2026-03-24 08:56:00'),
    (950100002, 900100001, 'POST_COMMENTED', 'POST', 910100001, 910100001, NULL, 900100003, 920100002, 1, '2026-03-24 08:57:00', '2026-03-24 08:57:00'),
    (950100003, 900100001, 'COMMENT_REPLIED', 'COMMENT', 920100001, 910100001, 920100001, 900100002, 920100003, 1, '2026-03-24 08:58:00', '2026-03-24 08:58:00')
ON DUPLICATE KEY UPDATE
    `post_id` = VALUES(`post_id`),
    `root_comment_id` = VALUES(`root_comment_id`),
    `last_actor_user_id` = VALUES(`last_actor_user_id`),
    `last_comment_id` = VALUES(`last_comment_id`),
    `unread_count` = VALUES(`unread_count`),
    `update_time` = VALUES(`update_time`);

COMMIT;
