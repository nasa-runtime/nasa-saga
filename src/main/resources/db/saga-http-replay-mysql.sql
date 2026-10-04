CREATE TABLE IF NOT EXISTS nasa_saga_http_replay_claim (
    producer VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nonce CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (producer, nonce),
    KEY idx_nasa_saga_http_replay_expiry (expires_at_ms)
) ENGINE=InnoDB;
