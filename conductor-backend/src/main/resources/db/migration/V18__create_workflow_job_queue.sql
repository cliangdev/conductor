-- workflow_job_queue: pending jobs waiting to be claimed by the execution engine
CREATE TABLE workflow_job_queue (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    run_id VARCHAR(36) NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    job_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_job_queue_claimed_at ON workflow_job_queue(claimed_at);
CREATE INDEX idx_workflow_job_queue_run_id ON workflow_job_queue(run_id);
