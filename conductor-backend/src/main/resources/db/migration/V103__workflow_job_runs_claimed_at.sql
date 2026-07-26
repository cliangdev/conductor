-- Records the moment a self-hosted daemon actually claims an AWAITING_PICKUP job, distinct from the
-- moment it was dispatched. JobDispatchPayloadService.buildPayload stamps this on the first successful
-- dispatch-payload fetch (idempotent -- a retry/restart re-fetch never moves it). Job status stays
-- AWAITING_PICKUP for the job's entire self-hosted execution (nothing else marks it RUNNING), so without
-- this column "unclaimed, waiting for a runner" and "claimed, actively running on the daemon" were
-- indistinguishable -- the bug behind the waitReason=AWAITING_RUNNER and Queued-filter fixes in the
-- same change. Nullable: unset until claimed, permanently null for every non-self-hosted job.
ALTER TABLE workflow_job_runs ADD COLUMN claimed_at TIMESTAMPTZ;
