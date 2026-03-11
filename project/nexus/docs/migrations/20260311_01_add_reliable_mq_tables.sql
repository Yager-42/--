CREATE TABLE IF NOT EXISTS reliable_mq_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    exchange_name VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload_type VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    headers_json TEXT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL,
    last_error VARCHAR(512) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reliable_mq_outbox_event_id (event_id),
    KEY idx_reliable_mq_outbox_status_retry (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS reliable_mq_replay_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    original_queue VARCHAR(128) NOT NULL,
    original_exchange VARCHAR(128) NOT NULL,
    original_routing_key VARCHAR(128) NOT NULL,
    payload_type VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL,
    last_error VARCHAR(512) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reliable_mq_replay_consumer_event (event_id, consumer_name),
    KEY idx_reliable_mq_replay_status_retry (status, next_retry_at)
);

CREATE TABLE IF NOT EXISTS reliable_mq_consumer_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    consumer_name VARCHAR(128) NOT NULL,
    payload_json TEXT NULL,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(512) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reliable_mq_consumer_event (event_id, consumer_name)
);
