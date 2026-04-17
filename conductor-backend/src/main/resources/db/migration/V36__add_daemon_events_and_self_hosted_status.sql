ALTER TYPE workflow_run_status ADD VALUE IF NOT EXISTS 'PENDING_LOCAL_PICKUP';
ALTER TYPE workflow_run_status ADD VALUE IF NOT EXISTS 'LOCAL_PICKUP_TIMEOUT';

CREATE TABLE daemon_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id),
    type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_daemon_events_project_acked_expires ON daemon_events(project_id, acked_at, expires_at);
