-- Add image and worker_job_id columns to workflow_step_runs.
-- image: the container image used for this step (nullable, existing rows will be NULL).
-- worker_job_id: external job ID returned by a remote worker (nullable, existing rows will be NULL).
ALTER TABLE workflow_step_runs ADD COLUMN image VARCHAR(512);
ALTER TABLE workflow_step_runs ADD COLUMN worker_job_id VARCHAR(255);
