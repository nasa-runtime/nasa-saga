CREATE TABLE IF NOT EXISTS saga_start_intent (
    intent_id VARCHAR(256) PRIMARY KEY,
    tenant_id VARCHAR(256) NOT NULL,
    workflow VARCHAR(128) NOT NULL,
    business_key VARCHAR(256) NOT NULL,
    saga_id VARCHAR(256) NOT NULL,
    definition_version INTEGER NOT NULL,
    expected_definition_digest CHAR(64),
    trigger_id VARCHAR(190) NOT NULL,
    deadline_at_ms BIGINT,
    request_body BYTEA NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    traceparent VARCHAR(55),
    business_slot_digest BYTEA NOT NULL UNIQUE,
    saga_id_digest BYTEA NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at_ms BIGINT,
    lease_owner VARCHAR(256),
    lease_until_ms BIGINT,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_http_status INTEGER,
    last_error_code VARCHAR(128),
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT chk_saga_start_digest CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_saga_start_expected_digest CHECK (
        expected_definition_digest IS NULL OR expected_definition_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_saga_start_state CHECK (
        state IN ('PENDING', 'IN_FLIGHT', 'COMMITTED', 'DUPLICATE', 'NEEDS_ATTENTION')
    ),
    CONSTRAINT chk_saga_start_counters CHECK (
        attempts >= 0 AND fencing_token >= 0 AND created_at_ms >= 0 AND updated_at_ms >= 0
    )
);
CREATE INDEX IF NOT EXISTS idx_saga_start_dispatch
    ON saga_start_intent (state, next_attempt_at_ms, lease_until_ms);
