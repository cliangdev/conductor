-- workflow_job_runs: each job's execution within a run
CREATE TYPE workflow_job_status AS ENUM ('PENDING','RUNNING','SUCCESS','FAILED','SKIPPED');
CREATE TABLE workflow_job_runs (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    run_id VARCHAR(36) NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    job_id VARCHAR(255) NOT NULL,
    status workflow_job_status NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_job_runs_run_id ON workflow_job_runs(run_id);
CREATE INDEX idx_workflow_job_runs_status ON workflow_job_runs(status);

-- workflow_step_runs: each step's execution within a job run
CREATE TYPE workflow_step_status AS ENUM ('PENDING','RUNNING','SUCCESS','FAILED','SKIPPED');
CREATE TABLE workflow_step_runs (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    job_run_id VARCHAR(36) NOT NULL REFERENCES workflow_job_runs(id) ON DELETE CASCADE,
    step_id VARCHAR(255),
    step_name VARCHAR(255) NOT NULL,
    step_type VARCHAR(64) NOT NULL,
    status workflow_step_status NOT NULL DEFAULT 'PENDING',
    log TEXT,
    output_json JSONB,
    error_reason TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_step_runs_job_run_id ON workflow_step_runs(job_run_id);
