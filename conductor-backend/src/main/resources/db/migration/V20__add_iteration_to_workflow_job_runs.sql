-- Add iteration column to workflow_job_runs for loop construct support.
-- Existing rows default to 0 (first iteration).
ALTER TABLE workflow_job_runs ADD COLUMN iteration INT NOT NULL DEFAULT 0;

-- Add LOOP_EXHAUSTED status for when a loop job runs out of iterations.
ALTER TYPE workflow_job_status ADD VALUE IF NOT EXISTS 'LOOP_EXHAUSTED';
