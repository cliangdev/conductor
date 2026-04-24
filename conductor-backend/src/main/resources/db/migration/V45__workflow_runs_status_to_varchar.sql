-- Convert workflow_runs.status from the custom Postgres enum type to VARCHAR so
-- JPA-derived queries (e.g. findByStatusAndStartedAtBefore) compare correctly
-- without needing per-query CAST or @ColumnTransformer hacks.
ALTER TABLE workflow_runs ALTER COLUMN status DROP DEFAULT;
ALTER TABLE workflow_runs ALTER COLUMN status TYPE VARCHAR(32) USING status::text;
ALTER TABLE workflow_runs ALTER COLUMN status SET DEFAULT 'PENDING';
DROP TYPE workflow_run_status;
