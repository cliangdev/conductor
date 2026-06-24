-- COND-18 E6: Outcome Metric time series on a Work Item. Stored as a JSONB array of observations
-- ({value, observedAt, note}); the metric's name/unit/direction come from the bound Workflow definition.
-- Follows the issue_tasks JSONB precedent — no relational series table.

ALTER TABLE issues ADD COLUMN outcome_metric JSONB;
