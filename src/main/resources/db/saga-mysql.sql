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

CREATE TABLE IF NOT EXISTS nasa_saga_http_replay_claim (
    producer VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (producer, nonce),
    KEY idx_nasa_saga_http_replay_expiry (expires_at_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS saga_participant_step (
    saga_id VARCHAR(256) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(256) NOT NULL,
    workflow_name VARCHAR(128) NOT NULL,
    definition_version INT UNSIGNED NOT NULL,
    definition_digest CHAR(64) NOT NULL,
    forward_status VARCHAR(32) NOT NULL,
    cancel_status VARCHAR(32) NOT NULL,
    compensation_status VARCHAR(32) NOT NULL,
    resolution_status VARCHAR(32) NOT NULL,
    execute_effect_id CHAR(36) NOT NULL,
    cancel_effect_id CHAR(36) NULL,
    compensate_effect_id CHAR(36) NULL,
    resolve_effect_id CHAR(36) NULL,
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
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (saga_id, step_name),
    UNIQUE KEY uk_execute_effect (execute_effect_id),
    CONSTRAINT chk_saga_participant_definition_digest CHECK (
        definition_digest IS NOT NULL
        AND CHAR_LENGTH(definition_digest) = 64
        AND BINARY definition_digest = BINARY LOWER(definition_digest)
        AND definition_digest NOT REGEXP '[^0-9a-f]'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS saga_participant_inbox (
    command_id CHAR(36) NOT NULL,
    effect_id CHAR(36) NOT NULL,
    saga_id VARCHAR(256) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    attempt INT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_event_id CHAR(36) NULL,
    traceparent VARCHAR(55) NULL,
    execute_input_digest CHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (command_id),
    KEY idx_saga_inbox_effect (effect_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS saga_result_outbox (
    event_id CHAR(36) NOT NULL,
    saga_id VARCHAR(256) NOT NULL,
    command_id CHAR(36) NOT NULL,
    payload LONGBLOB NOT NULL,
    traceparent VARCHAR(55) NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at_ms BIGINT NULL,
    lease_owner VARCHAR(256) NULL,
    lease_until_ms BIGINT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(128) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    KEY idx_saga_result_dispatch (state, next_attempt_at_ms, lease_until_ms),
    CONSTRAINT chk_saga_result_state CHECK (
        state IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'NEEDS_ATTENTION')
    ),
    CONSTRAINT chk_saga_result_counters CHECK (attempts >= 0 AND fencing_token >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
