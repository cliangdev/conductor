-- Typed fields and a due date on Work Items.
--
-- Workflow-agnostic: a Workflow declares the field keys it wants (workflow-definition `fields`) and the
-- values live here, so any lifecycle can carry structured data beyond title/description without a new
-- column per Workflow. Both columns are nullable; most Work Items carry neither.
--
--   fields  JSONB object {key: value}, validated against the bound Workflow's declared fields on write
--   due_on  calendar date the item is due (a review date, a deadline), distinct from scheduled_for,
--           which is an instant that drives publishing

ALTER TABLE work_items ADD COLUMN fields JSONB;
ALTER TABLE work_items ADD COLUMN due_on DATE;

-- "What is overdue?" scans only the dated minority of a project's items.
CREATE INDEX idx_work_items_project_due_on
    ON work_items (project_id, due_on)
    WHERE due_on IS NOT NULL;
