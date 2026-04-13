-- Create workflow_schedule_skips table to audit skipped schedule firings.
-- FK to workflow_schedules cascades on delete.
-- run_id is nullable: it is set when the skip is associated with a concurrent run that caused the skip.
CREATE TABLE workflow_schedule_skips (
    id          VARCHAR(36) PRIMARY KEY,
    schedule_id VARCHAR(36) NOT NULL REFERENCES workflow_schedules(id) ON DELETE CASCADE,
    skipped_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reason      TEXT,
    run_id      VARCHAR(36)
);

CREATE INDEX idx_workflow_schedule_skips_schedule_id
    ON workflow_schedule_skips(schedule_id);
