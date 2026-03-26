-- frontend rollback batch: frontend_seed_20260324_v1
-- target db: nexus_social

SET NAMES utf8mb4;
USE `nexus_social`;

START TRANSACTION;

DELETE FROM `interaction_notification`
WHERE `notification_id` IN (950100001, 950100002, 950100003);

DELETE FROM `interaction_reaction`
WHERE (`target_type`, `target_id`, `reaction_type`, `user_id`) IN (
    ('POST', 910100001, 'LIKE', 900100001),
    ('POST', 910100001, 'LIKE', 900100003),
    ('POST', 910100001, 'LIKE', 900100004),
    ('POST', 910100002, 'LIKE', 900100001),
    ('COMMENT', 920100001, 'LIKE', 900100002),
    ('COMMENT', 920100001, 'LIKE', 900100004)
);

DELETE FROM `interaction_reaction_count`
WHERE (`target_type`, `target_id`, `reaction_type`) IN (
    ('POST', 910100001, 'LIKE'),
    ('POST', 910100002, 'LIKE'),
    ('COMMENT', 920100001, 'LIKE')
);

DELETE FROM `interaction_comment_pin`
WHERE `post_id` = 910100001;

DELETE FROM `interaction_comment`
WHERE `comment_id` IN (920100001, 920100002, 920100003);

DELETE FROM `content_post_type`
WHERE `post_id` IN (910100001, 910100002);

DELETE FROM `content_post`
WHERE `post_id` IN (910100001, 910100002);

DELETE FROM `user_follower`
WHERE `id` IN (941100001, 941100002, 941100003, 941100004);

DELETE FROM `user_relation`
WHERE `id` IN (940100001, 940100002, 940100003, 940100004);

DELETE FROM `auth_user_role`
WHERE `id` = 930100011;

DELETE FROM `auth_account`
WHERE `account_id` = 930100001;

DELETE FROM `user_privacy_setting`
WHERE `user_id` IN (900100001, 900100002, 900100003, 900100004);

DELETE FROM `user_status`
WHERE `user_id` IN (900100001, 900100002, 900100003, 900100004);

DELETE FROM `user_base`
WHERE `user_id` IN (900100001, 900100002, 900100003, 900100004);

COMMIT;
