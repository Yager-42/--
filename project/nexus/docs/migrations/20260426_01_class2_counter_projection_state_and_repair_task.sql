CREATE TABLE IF NOT EXISTS class2_counter_projection_state (
  projection_key varchar(128) NOT NULL,
  projection_type varchar(32) NOT NULL,
  last_version bigint NOT NULL,
  update_time datetime NOT NULL,
  PRIMARY KEY (projection_key)
);

-- projection_key is the unique business ordering identity.
-- projection_type is audit metadata and must remain stable for a given projection_key.

CREATE TABLE IF NOT EXISTS class2_user_counter_repair_task (
  task_id bigint NOT NULL,
  repair_type varchar(32) NOT NULL,
  user_id bigint NOT NULL,
  dedupe_key varchar(64) NOT NULL,
  status varchar(16) NOT NULL,
  retry_count int NOT NULL,
  claim_owner varchar(64) DEFAULT NULL,
  claimed_at datetime DEFAULT NULL,
  lease_until datetime DEFAULT NULL,
  next_retry_time datetime NOT NULL,
  reason varchar(255) DEFAULT NULL,
  last_error varchar(255) DEFAULT NULL,
  create_time datetime NOT NULL,
  update_time datetime NOT NULL,
  PRIMARY KEY (task_id),
  UNIQUE KEY uk_class2_user_counter_repair_task_dedupe (dedupe_key),
  KEY idx_class2_user_counter_repair_task_claim (status, next_retry_time, lease_until)
);

