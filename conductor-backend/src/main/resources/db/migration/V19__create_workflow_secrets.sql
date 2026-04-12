-- workflow_secrets: AES-256-GCM encrypted per-project secrets
CREATE TABLE workflow_secrets (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    key VARCHAR(64) NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_workflow_secrets_project_key ON workflow_secrets(project_id, key);
CREATE INDEX idx_workflow_secrets_project_id ON workflow_secrets(project_id);
