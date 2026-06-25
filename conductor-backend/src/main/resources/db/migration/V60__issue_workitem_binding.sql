-- COND-18 E2: bind issues (Work Items) to a Workflow definition.
-- The issues table is reused (the "Work Item" rename is API/UI-only in v1); these nullable
-- columns let the lifecycle WorkflowEngine own transitions for workflow-bound issues while
-- legacy/unbound rows fall through to the built-in ENGINEERING workflow (which reproduces
-- today's exact transition map — zero behavior change, AC-P0-1.1).

ALTER TABLE issues
    ADD COLUMN workflow            VARCHAR(64),
    ADD COLUMN workflow_version    INTEGER,
    ADD COLUMN current_status      VARCHAR(48),
    ADD COLUMN state_context       JSONB,
    ADD COLUMN parent_work_item_id VARCHAR(36);

-- Fan-out children point at their parent Work Item (COND-18 create-sub-items step).
ALTER TABLE issues
    ADD CONSTRAINT issues_parent_work_item_fk
        FOREIGN KEY (parent_work_item_id) REFERENCES issues(id) ON DELETE SET NULL;

-- Backfill every existing issue onto the built-in ENGINEERING workflow at its current status.
UPDATE issues
SET workflow         = 'ENGINEERING',
    workflow_version = 1,
    current_status   = status::text
WHERE workflow IS NULL;
