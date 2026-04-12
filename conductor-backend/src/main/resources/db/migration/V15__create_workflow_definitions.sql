-- workflow_definitions: stores the YAML workflow config per project
CREATE TABLE workflow_definitions (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    yaml TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    webhook_token VARCHAR(64),  -- unique stable token for webhook trigger
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_workflow_definitions_project_id ON workflow_definitions(project_id);
CREATE UNIQUE INDEX idx_workflow_definitions_project_name ON workflow_definitions(project_id, name);
CREATE UNIQUE INDEX idx_workflow_definitions_webhook_token ON workflow_definitions(webhook_token) WHERE webhook_token IS NOT NULL;
