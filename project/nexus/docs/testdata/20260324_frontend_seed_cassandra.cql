-- frontend seed batch: frontend_seed_20260324_v1
-- target keyspace: nexus_kv

USE nexus_kv;

INSERT INTO note_content (id, content)
VALUES (11111111-1111-4111-8111-111111111111, 'Alpha body for frontend smoke tests. This post is stored in Cassandra and linked from MySQL post 910100001.');

INSERT INTO note_content (id, content)
VALUES (22222222-2222-4222-8222-222222222222, 'Beta body for frontend smoke tests. This post is stored in Cassandra and linked from MySQL post 910100002.');

INSERT INTO comment_content (note_id, year_month, content_id, content)
VALUES (910100001, '2026-03', aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1, 'Root comment from the seed viewer on post 910100001.');

INSERT INTO comment_content (note_id, year_month, content_id, content)
VALUES (910100001, '2026-03', bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2, 'Second root comment from Author Beta on post 910100001.');

INSERT INTO comment_content (note_id, year_month, content_id, content)
VALUES (910100001, '2026-03', cccccccc-cccc-4ccc-8ccc-ccccccccccc3, 'Reply from Author Alpha to the seed viewer comment.');
