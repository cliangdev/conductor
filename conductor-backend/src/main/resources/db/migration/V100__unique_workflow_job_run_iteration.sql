-- DB-level backstop against dispatching the same job execution twice (bulletproofing follow-up
-- flagged but never built alongside the self-hosted AWAITING_PICKUP dedup in V-era commit bd8ab5bb).
-- WorkflowJobOrchestrator.planJobExecution now takes a pessimistic run-row lock unconditionally and
-- skips duplicate dispatch when the latest row for (run, job) is already AWAITING_PICKUP/RUNNING —
-- this index guarantees that invariant at the database level too, in case a future code path
-- reintroduces the race. run_id, job_id, and iteration are all NOT NULL (iteration defaults to 0
-- per V20), so a plain unique index — no partial WHERE clause — is sufficient.
CREATE UNIQUE INDEX idx_workflow_job_runs_run_job_iteration
    ON workflow_job_runs (run_id, job_id, iteration);
