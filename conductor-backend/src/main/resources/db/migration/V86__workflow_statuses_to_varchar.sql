-- Convert workflow_job_runs.status and workflow_step_runs.status from native Postgres enum types to
-- VARCHAR, mirroring V45 (workflow_runs.status), so JPA-derived queries compare correctly without
-- per-query CAST or @ColumnTransformer hacks.
ALTER TABLE workflow_job_runs ALTER COLUMN status DROP DEFAULT;
ALTER TABLE workflow_job_runs ALTER COLUMN status TYPE VARCHAR(32) USING status::text;
ALTER TABLE workflow_job_runs ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE workflow_step_runs ALTER COLUMN status DROP DEFAULT;
ALTER TABLE workflow_step_runs ALTER COLUMN status TYPE VARCHAR(32) USING status::text;
ALTER TABLE workflow_step_runs ALTER COLUMN status SET DEFAULT 'PENDING';

DROP TYPE workflow_job_status;
DROP TYPE workflow_step_status;
