-- Cassandra KV schema (xiaohashu style)
-- Note: adjust replication settings for production.

CREATE KEYSPACE IF NOT EXISTS nexus_kv
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE nexus_kv;

-- Note/Post content KV
CREATE TABLE IF NOT EXISTS note_content (
  id uuid PRIMARY KEY,
  content text
);

-- Comment content KV (partitioned by note_id + year_month)
CREATE TABLE IF NOT EXISTS comment_content (
  note_id bigint,
  year_month text,
  content_id uuid,
  content text,
  PRIMARY KEY ((note_id, year_month), content_id)
);

