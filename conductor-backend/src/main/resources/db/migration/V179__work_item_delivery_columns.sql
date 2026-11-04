-- Delivery tracking on Work Items: priority, milestone, appetite (a time box) and cycle timestamps.
-- Additive and nullable only (preview deploys share the prod DB). The milestone FK arrives with the
-- milestones table in V180; the column comes first so the partial index below can sit beside the others.
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS priority      SMALLINT;
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS milestone_id  VARCHAR(36);
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS appetite      VARCHAR(32);
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS appetite_days INTEGER;
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS started_at     TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_items ADD COLUMN IF NOT EXISTS completed_at   TIMESTAMP WITH TIME ZONE;

-- P0..P3 stored as 0..3; NULL means "no priority".
ALTER TABLE work_items ADD CONSTRAINT chk_work_items_priority
    CHECK (priority IS NULL OR priority BETWEEN 0 AND 3);
ALTER TABLE work_items ADD CONSTRAINT chk_work_items_appetite_days
    CHECK (appetite_days IS NULL OR appetite_days BETWEEN 1 AND 365);

-- Partial: most rows have no parent, priority or milestone, so the indexes stay small.
CREATE INDEX IF NOT EXISTS idx_work_items_parent
    ON work_items (parent_work_item_id) WHERE parent_work_item_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_items_project_priority
    ON work_items (project_id, priority) WHERE priority IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_items_project_milestone
    ON work_items (project_id, milestone_id) WHERE milestone_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_items_project_completed
    ON work_items (project_id, completed_at) WHERE completed_at IS NOT NULL;

-- Items already finished before cycle timestamps existed: the last touch is the best available guess,
-- and it is what throughput forecasting reads. started_at stays null for them (unknown, not guessed).
UPDATE work_items
   SET completed_at = updated_at
 WHERE workflow = 'ENGINEERING'
   AND current_status IN ('DONE', 'CLOSED')
   AND completed_at IS NULL;
