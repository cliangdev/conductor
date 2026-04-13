-- Create workflow_schedules table for cron-based workflow triggers.
-- FK to workflow_definitions cascades on delete so orphan schedules are cleaned up automatically.
CREATE TABLE workflow_schedules (
    id                VARCHAR(36)  PRIMARY KEY,
    workflow_id       VARCHAR(36)  NOT NULL REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    cron_expression   VARCHAR(255) NOT NULL,
    timezone          VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    last_run_at       TIMESTAMP WITH TIME ZONE,
    next_run_at       TIMESTAMP WITH TIME ZONE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Partial index speeds up the scheduler query that looks for the next due schedule.
CREATE INDEX idx_workflow_schedules_next_run_at_enabled
    ON workflow_schedules(next_run_at)
    WHERE enabled = TRUE;
