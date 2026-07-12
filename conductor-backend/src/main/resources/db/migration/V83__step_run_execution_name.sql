-- Persists the Cloud Run execution resource name so a backend restart can re-attach to an
-- in-flight execution instead of relaunching a duplicate.
ALTER TABLE workflow_step_runs ADD COLUMN execution_name VARCHAR(512);
