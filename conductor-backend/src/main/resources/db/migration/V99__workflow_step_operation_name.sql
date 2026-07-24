-- The Cloud Run RunJob long-running operation's resource name, persisted as soon as Cloud Run
-- acknowledges the launch request -- before the Execution itself has necessarily materialized (see
-- GcpCloudRunJobLauncher). Durability point for a backend restart mid-launch: with this persisted,
-- a stuck step can resolve its execution_name later instead of relaunching a duplicate execution
-- (RunJobRequest has no idempotency key).
ALTER TABLE workflow_step_runs
    ADD COLUMN operation_name VARCHAR(512);
