-- Generic per-item scheduling on work_items.
--
-- Workflow-agnostic on purpose: any Workflow (engineering, marketing, knowledge, ...) can put a Work Item
-- on a clock, so nothing here carries domain vocabulary. Both columns are nullable — an unscheduled Work
-- Item is the norm.
--
--   scheduled_for     the instant the item is due, stored as timestamptz (UTC on the wire)
--   schedule_timezone the IANA zone id the schedule was authored in (e.g. America/New_York), kept
--                     alongside the instant so a recurring/local-wall-clock reading of the schedule
--                     survives DST without re-deriving the author's intent

ALTER TABLE work_items ADD COLUMN scheduled_for TIMESTAMPTZ;
ALTER TABLE work_items ADD COLUMN schedule_timezone VARCHAR(64);

-- Partial index for the due-item poll: scheduled items are the small minority, so indexing only the
-- non-null rows keeps the index tiny and the "what is due now?" scan cheap.
CREATE INDEX idx_work_items_scheduled_for
    ON work_items (scheduled_for)
    WHERE scheduled_for IS NOT NULL;
