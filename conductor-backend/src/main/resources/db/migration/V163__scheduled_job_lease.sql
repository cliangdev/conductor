-- One row per timed job (see com.conductor.scheduling.ScheduledJobRegistry): who holds it right now, and
-- how its last run went. A tick claims the row with a single conditional upsert (leased_until has passed
-- and the last start is old enough), runs with no transaction open, then releases it. The row is the only
-- coordination between instances, so two overlapping triggers (in-app timer, Cloud Scheduler) never run
-- the same job at once.
CREATE TABLE scheduled_job_lease (
    job_name         VARCHAR(100) PRIMARY KEY,
    leased_until     TIMESTAMP WITH TIME ZONE NOT NULL,
    leased_by        VARCHAR(100),
    last_started_at  TIMESTAMP WITH TIME ZONE,
    last_finished_at TIMESTAMP WITH TIME ZONE,
    last_status      VARCHAR(20),
    last_error       TEXT
);
