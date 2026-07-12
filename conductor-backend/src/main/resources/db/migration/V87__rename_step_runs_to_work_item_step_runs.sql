-- Disambiguates the "step run" vocabulary: this table records an agent-run Step on a Work Item
-- (COND-18 P0-6), distinct from the workflow-automation-engine's workflow_step_runs table.
ALTER TABLE step_runs RENAME TO work_item_step_runs;

ALTER TABLE work_item_step_runs RENAME CONSTRAINT step_runs_pkey TO work_item_step_runs_pkey;
ALTER TABLE work_item_step_runs RENAME CONSTRAINT step_runs_issue_id_fkey TO work_item_step_runs_work_item_id_fkey;
ALTER INDEX idx_step_runs_work_item_id RENAME TO idx_work_item_step_runs_work_item_id;
