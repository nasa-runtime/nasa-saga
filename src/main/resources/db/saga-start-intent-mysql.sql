CREATE TABLE IF NOT EXISTS saga_start_intent (
    intent_id VARCHAR(256) NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    workflow VARCHAR(128) NOT NULL,
    business_key VARCHAR(256) NOT NULL,
    saga_id VARCHAR(256) NOT NULL,
    definition_version INT UNSIGNED NOT NULL,
    expected_definition_digest CHAR(64) NULL,
    trigger_id VARCHAR(190) NOT NULL,
    deadline_at_ms BIGINT NULL,
    request_body LONGBLOB NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    traceparent VARCHAR(55) NULL,
    business_slot_digest VARBINARY(32) NOT NULL,
    saga_id_digest VARBINARY(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at_ms BIGINT NULL,
    lease_owner VARCHAR(256) NULL,
    lease_until_ms BIGINT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_http_status INT NULL,
    last_error_code VARCHAR(128) NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (intent_id),
    UNIQUE KEY uk_saga_start_slot (business_slot_digest),
    UNIQUE KEY uk_saga_start_identity (saga_id_digest),
    KEY idx_saga_start_dispatch (state, next_attempt_at_ms, lease_until_ms),
    CONSTRAINT chk_saga_start_digest CHECK (request_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_saga_start_expected_digest CHECK (
        expected_definition_digest IS NULL OR expected_definition_digest REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_saga_start_state CHECK (
        state IN ('PENDING', 'IN_FLIGHT', 'COMMITTED', 'DUPLICATE', 'NEEDS_ATTENTION')
    ),
    CONSTRAINT chk_saga_start_counters CHECK (
        attempts >= 0 AND fencing_token >= 0 AND created_at_ms >= 0 AND updated_at_ms >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
