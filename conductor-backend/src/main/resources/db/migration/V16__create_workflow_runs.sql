-- workflow_runs: each execution of a workflow
CREATE TYPE workflow_run_status AS ENUM ('PENDING','RUNNING','SUCCESS','FAILED','CANCELLED');
CREATE TABLE workflow_runs (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    workflow_id VARCHAR(36) NOT NULL REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    trigger_type VARCHAR(64) NOT NULL,
    event_payload JSONB,
    status workflow_run_status NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_runs_workflow_id ON workflow_runs(workflow_id);
CREATE INDEX idx_workflow_runs_status ON workflow_runs(status);
