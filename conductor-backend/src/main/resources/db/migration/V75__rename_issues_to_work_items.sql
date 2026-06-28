-- Phase 1 of the Issue -> Work Item migration: rename the internal domain table and its
-- per-table foreign-key columns to the Work Item vocabulary. The external /api/v1 REST contract
-- is unchanged (controllers and generated DTOs still speak "issue"); this is a storage-layer rename.
--
-- Postgres table/column/index renames are metadata-only operations: existing foreign keys, data,
-- and constraints are preserved across the rename (FKs track the column by identity, not by name).

-- 1. Rename the two core tables.
ALTER TABLE issues RENAME TO work_items;
ALTER TABLE issue_reviewers RENAME TO work_item_reviewers;

-- 2. Rename the issue_id foreign-key column to work_item_id on every child table.
ALTER TABLE documents          RENAME COLUMN issue_id TO work_item_id;
ALTER TABLE reviews            RENAME COLUMN issue_id TO work_item_id;
ALTER TABLE comments           RENAME COLUMN issue_id TO work_item_id;
ALTER TABLE assets             RENAME COLUMN issue_id TO work_item_id;
ALTER TABLE step_runs          RENAME COLUMN issue_id TO work_item_id;
ALTER TABLE work_item_reviewers RENAME COLUMN issue_id TO work_item_id;

-- 3. Rename indexes that embed "issue" in their name (metadata-only, low risk).
--    work_items own indexes:
ALTER INDEX IF EXISTS idx_issues_project_id             RENAME TO idx_work_items_project_id;
ALTER INDEX IF EXISTS idx_issues_project_current_status RENAME TO idx_work_items_project_current_status;
ALTER INDEX IF EXISTS idx_issues_assignee              RENAME TO idx_work_items_assignee;
ALTER INDEX IF EXISTS idx_issues_project_sequence      RENAME TO idx_work_items_project_sequence;
ALTER INDEX IF EXISTS idx_issues_project_workflow      RENAME TO idx_work_items_project_workflow;
--    child-table issue_id indexes:
ALTER INDEX IF EXISTS idx_assets_issue_id    RENAME TO idx_assets_work_item_id;
ALTER INDEX IF EXISTS idx_step_runs_issue_id RENAME TO idx_step_runs_work_item_id;

-- 4. Drop the legacy native-enum columns now that the Workflow-validated string columns
--    (current_status, item_type) are the sole authority (see V69). Dropping the status column
--    automatically drops idx_issues_project_status; dropping the columns frees the enum types.
ALTER TABLE work_items DROP COLUMN status;
ALTER TABLE work_items DROP COLUMN type;
DROP TYPE issue_status;
DROP TYPE issue_type;

-- 5. item_type is now unconditionally set by the application on every insert (the old revision that
--    omitted it has retired), so it can finally be promoted to NOT NULL (deferred from V69).
ALTER TABLE work_items ALTER COLUMN item_type SET NOT NULL;

-- 6. Rename the named unique constraints that still embed the "issue" vocabulary, so no stale
--    legacy names remain after the table rename (cosmetic but completes the rename).
ALTER TABLE work_items           RENAME CONSTRAINT uq_issues_project_sequence TO uq_work_items_project_sequence;
ALTER TABLE work_item_reviewers  RENAME CONSTRAINT uq_issue_reviewer          TO uq_work_item_reviewer;
