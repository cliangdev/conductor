-- A knowledge source whose librarian run failed inside the agent (out of turns or time) is claimed on
-- its own from then on, so it can't take its batch-mates to DEAD with it. The flag survives
-- POST /knowledge/sources/retry, which resets attempts.
ALTER TABLE knowledge_sources ADD COLUMN isolate_on_retry BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: DEAD sources whose last run failed that way (e.g. the error_max_turns runs of 2026-11),
-- so a retry of them goes one source at a time.
UPDATE knowledge_sources ks
SET isolate_on_retry = TRUE
WHERE ks.status = 'DEAD'
  AND ks.processing_run_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM workflow_job_runs jr
      JOIN workflow_step_runs sr ON sr.job_run_id = jr.id
      WHERE jr.run_id = ks.processing_run_id
        AND sr.error_reason IN ('CLAUDE_AGENT_ERROR', 'CLAUDE_TIMEOUT'));
