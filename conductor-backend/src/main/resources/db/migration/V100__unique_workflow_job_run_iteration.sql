-- DB-level backstop against dispatching the same job execution twice (bulletproofing follow-up
-- flagged but never built alongside the self-hosted AWAITING_PICKUP dedup in V-era commit bd8ab5bb).
-- WorkflowJobOrchestrator.planJobExecution now takes a pessimistic run-row lock unconditionally and
-- skips duplicate dispatch when the latest row for (run, job) is already AWAITING_PICKUP/RUNNING —
-- this index guarantees that invariant at the database level too, in case a future code path
-- reintroduces the race. run_id, job_id, and iteration are all NOT NULL (iteration defaults to 0
-- per V20), so a plain unique index — no partial WHERE clause — is sufficient.
--
-- The exact race this migration guards against has already produced live duplicate rows (observed
-- on a "review_backend" job dispatched twice), so creating the index outright could fail against an
-- affected database. Dedupe first: for each (run_id, job_id, iteration) group, keep only the most
-- recently started row (ties broken by id) and drop the rest — workflow_step_runs cascades on
-- job_run_id, workflow_artifacts sets it null, so this is safe to do unconditionally.
DELETE FROM workflow_job_runs t
USING (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY run_id, job_id, iteration
               ORDER BY started_at DESC NULLS LAST, id DESC
           ) AS rn
    FROM workflow_job_runs
) ranked
WHERE t.id = ranked.id
  AND ranked.rn > 1;

CREATE UNIQUE INDEX idx_workflow_job_runs_run_job_iteration
    ON workflow_job_runs (run_id, job_id, iteration);
