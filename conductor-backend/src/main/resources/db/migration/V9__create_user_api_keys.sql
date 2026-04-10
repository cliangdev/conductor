CREATE TABLE user_api_keys (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id),
    label VARCHAR(100) NOT NULL DEFAULT 'CLI Key',
    key_hash VARCHAR(255) NOT NULL,
    key_suffix VARCHAR(4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_user_api_keys_user_id ON user_api_keys(user_id);
