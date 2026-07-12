-- Per-project runtime targets: named, customer-owned places claude-code jobs can run
-- ("runs-on: <name>"), backed by a connection to a provider (first: gcp-cloud-run).

CREATE TABLE runtime_targets (
    id             VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id     VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name           VARCHAR(64) NOT NULL,
    provider       VARCHAR(32) NOT NULL,
    connection_id  VARCHAR(36) REFERENCES connection(id) ON DELETE SET NULL,
    config_json    JSONB NOT NULL DEFAULT '{}',
    status         VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING',
    error_message  TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_runtime_target_project_name UNIQUE (project_id, name)
);
CREATE INDEX idx_runtime_targets_project_id ON runtime_targets (project_id);
