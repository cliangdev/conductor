-- Circuit breaker for a workflow whose runs keep failing (see WorkflowFailureCircuitBreaker):
-- consecutive_failures resets to 0 on any SUCCESS and increments on FAILED; at the trip threshold
-- the workflow is auto-disabled (enabled=false) and these auto_pause_* columns record why/when/which
-- run tripped it, so the UI can distinguish "a human turned this off" from "the system paused it" and
-- link straight to the failing run. auto_pause_reason is a free-form code (only "CONSECUTIVE_FAILURES"
-- today) rather than an enum column, so a future trip condition (e.g. credential revoked, quota
-- exceeded) doesn't need a migration. auto_paused_run_id has no FK -- same style as
-- knowledge_source.processing_run_id -- since a run can be pruned independently of this pointer.
ALTER TABLE workflow_definitions
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN auto_paused_at TIMESTAMPTZ,
    ADD COLUMN auto_pause_reason VARCHAR(64),
    ADD COLUMN auto_paused_run_id VARCHAR(36);
