CREATE TABLE IF NOT EXISTS saga_participant_step (
    saga_id VARCHAR(256) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    workflow_name VARCHAR(128) NOT NULL,
    definition_version BIGINT NOT NULL CHECK (definition_version > 0),
    definition_digest CHAR(64) NOT NULL,
    forward_status VARCHAR(32) NOT NULL,
    cancel_status VARCHAR(32) NOT NULL,
    compensation_status VARCHAR(32) NOT NULL,
    resolution_status VARCHAR(32) NOT NULL,
    execute_effect_id CHAR(36) NOT NULL,
    cancel_effect_id CHAR(36),
    compensate_effect_id CHAR(36),
    resolve_effect_id CHAR(36),
    execute_result_status VARCHAR(32) NULL,
    execute_result_terminal_status VARCHAR(32) NULL,
    execute_result_reason_code VARCHAR(64) NULL,
    cancel_result_status VARCHAR(32) NULL,
    cancel_result_terminal_status VARCHAR(32) NULL,
    cancel_result_reason_code VARCHAR(64) NULL,
    compensate_result_status VARCHAR(32) NULL,
    compensate_result_terminal_status VARCHAR(32) NULL,
    compensate_result_reason_code VARCHAR(64) NULL,
    resolve_result_status VARCHAR(32) NULL,
    resolve_result_terminal_status VARCHAR(32) NULL,
    resolve_result_reason_code VARCHAR(64) NULL,
    execute_input_digest CHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (saga_id, step_name),
    CONSTRAINT chk_saga_participant_definition_digest
        CHECK (definition_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT saga_participant_execute_effect UNIQUE (execute_effect_id)
);

CREATE TABLE IF NOT EXISTS saga_participant_inbox (
    command_id CHAR(36) PRIMARY KEY,
    effect_id CHAR(36) NOT NULL,
    saga_id VARCHAR(256) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_event_id CHAR(36),
    traceparent VARCHAR(55),
    execute_input_digest CHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_saga_inbox_effect
    ON saga_participant_inbox (effect_id);

CREATE TABLE IF NOT EXISTS saga_result_outbox (
    event_id CHAR(36) PRIMARY KEY,
    saga_id VARCHAR(256) NOT NULL,
    command_id CHAR(36) NOT NULL,
    payload BYTEA NOT NULL,
    traceparent VARCHAR(55),
    state VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at_ms BIGINT,
    lease_owner VARCHAR(256),
    lease_until_ms BIGINT,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(128) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_saga_result_state CHECK (
        state IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'NEEDS_ATTENTION')
    ),
    CONSTRAINT chk_saga_result_counters CHECK (attempts >= 0 AND fencing_token >= 0)
);
CREATE INDEX IF NOT EXISTS idx_saga_result_dispatch
    ON saga_result_outbox (state, next_attempt_at_ms, lease_until_ms);
