-- Unified connector framework: one Connection instance entity (folds integration_credentials +
-- project_repositories), a per-connection data cache, and a generic inbound webhook event log.

-- A single connected instance of a connector for a project. Many rows per (project, connector)
-- are allowed (multi-instance, e.g. one GitHub repo per row); single-instance is enforced in service code.
CREATE TABLE connection (
    id                       VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id               VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id             VARCHAR(64) NOT NULL,
    display_label            VARCHAR(160),
    auth_type                VARCHAR(32) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    encrypted_access_token   TEXT,
    encrypted_refresh_token  TEXT,
    encrypted_webhook_secret TEXT,
    kms_key_reference        TEXT,
    token_expires_at         TIMESTAMP WITH TIME ZONE,
    config_json              JSONB NOT NULL DEFAULT '{}',
    visibility_policy        JSONB NOT NULL DEFAULT '{"minRole":"REVIEWER"}',
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    connected_by             VARCHAR(36)
);
CREATE INDEX idx_connection_project_connector ON connection (project_id, connector_id);

-- Last-successful fetch result for a pull connection; UPSERT per connection on each successful fetch.
CREATE TABLE connection_data_cache (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    connection_id VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    data_json     JSONB NOT NULL,
    health_status VARCHAR(32) NOT NULL DEFAULT 'HEALTHY',
    fetched_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_connection_data_cache UNIQUE (connection_id)
);

-- Generic inbound webhook event log (idempotency + retry/dead-letter), connector/connection agnostic.
CREATE TABLE webhook_event (
    id                VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    connector_id      VARCHAR(64) NOT NULL,
    connection_id     VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    delivery_id       VARCHAR(255) UNIQUE,
    event_type        VARCHAR(100),
    payload           TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts          INT NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMP WITH TIME ZONE,
    error_message     TEXT,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhook_event_status ON webhook_event (status, last_attempted_at);
CREATE INDEX idx_webhook_event_connection ON webhook_event (connection_id);
