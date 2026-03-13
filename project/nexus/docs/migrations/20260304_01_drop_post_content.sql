-- Drop MySQL KV adapter table for post content.
-- Post body is stored in Cassandra (nexus_kv.note_content).

DROP TABLE IF EXISTS `post_content`;

