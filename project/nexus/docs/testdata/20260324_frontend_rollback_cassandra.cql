-- frontend rollback batch: frontend_seed_20260324_v1
-- target keyspace: nexus_kv

USE nexus_kv;

DELETE FROM note_content
WHERE id = 11111111-1111-4111-8111-111111111111;

DELETE FROM note_content
WHERE id = 22222222-2222-4222-8222-222222222222;

DELETE FROM comment_content
WHERE note_id = 910100001
  AND year_month = '2026-03'
  AND content_id = aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1;

DELETE FROM comment_content
WHERE note_id = 910100001
  AND year_month = '2026-03'
  AND content_id = bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2;

DELETE FROM comment_content
WHERE note_id = 910100001
  AND year_month = '2026-03'
  AND content_id = cccccccc-cccc-4ccc-8ccc-ccccccccccc3;
