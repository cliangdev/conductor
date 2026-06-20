-- A project's connection to a vendor connector
CREATE TABLE integration_credentials (
    id                      VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id              VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id            VARCHAR(64) NOT NULL,
    auth_type               VARCHAR(32) NOT NULL,
    encrypted_access_token  TEXT,
    encrypted_refresh_token TEXT,
    kms_key_reference       TEXT,
    token_expires_at        TIMESTAMP WITH TIME ZONE,
    config_json             JSONB NOT NULL DEFAULT '{}',
    visibility_policy        JSONB NOT NULL DEFAULT '{"minRole":"REVIEWER"}',
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_integration_credentials UNIQUE (project_id, connector_id)
);

-- Last-successful fetch result; UPSERT on each successful fetch
CREATE TABLE integration_data_cache (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id    VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id  VARCHAR(64) NOT NULL,
    data_json     JSONB NOT NULL,
    health_status VARCHAR(32) NOT NULL DEFAULT 'HEALTHY',
    fetched_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_integration_data_cache UNIQUE (project_id, connector_id)
);

-- Short-lived CSRF state tokens for OAuth flows
CREATE TABLE integration_oauth_states (
    state        VARCHAR(64) PRIMARY KEY,
    project_id   VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id VARCHAR(64) NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_integration_credentials_project ON integration_credentials (project_id);
CREATE INDEX idx_integration_data_cache_project ON integration_data_cache (project_id);
CREATE INDEX idx_integration_oauth_states_expires ON integration_oauth_states (expires_at);
