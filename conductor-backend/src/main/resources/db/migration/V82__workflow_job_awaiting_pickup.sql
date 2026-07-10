-- AWAITING_PICKUP: a self-hosted job dispatched to the daemon via a pointer DaemonEvent,
-- waiting for the daemon to fetch its dispatch payload and run it.
ALTER TYPE workflow_job_status ADD VALUE IF NOT EXISTS 'AWAITING_PICKUP';
