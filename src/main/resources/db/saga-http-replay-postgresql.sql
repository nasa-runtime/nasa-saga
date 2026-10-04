CREATE TABLE IF NOT EXISTS nasa_saga_http_replay_claim (
    producer VARCHAR(128) NOT NULL,
    nonce CHAR(32) NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (producer, nonce)
);
CREATE INDEX IF NOT EXISTS idx_nasa_saga_http_replay_expiry
    ON nasa_saga_http_replay_claim (expires_at_ms);
