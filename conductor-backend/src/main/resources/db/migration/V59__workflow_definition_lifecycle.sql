-- COND-18 E1: extend workflow_definitions with the Workflow lifecycle layer.
-- The existing table holds YAML automations; COND-18 broadens it into the unified
-- Workflow: a versioned, publishable statechart stored as JSONB. We EXTEND (not fork)
-- the table and engine; existing YAML automations become "Workflows with no statuses".
-- See docs/workflow-definition-schema.md and schema/workflow-definition-v1.schema.json.

ALTER TABLE workflow_definitions
    ADD COLUMN definition     JSONB,
    ADD COLUMN version        INTEGER,
    ADD COLUMN state          VARCHAR(16),
    ADD COLUMN area           VARCHAR(64),
    ADD COLUMN schema_version INTEGER;

-- Only a PUBLISHED version is bindable by Work Items; the Builder accumulates edits in a DRAFT.
ALTER TABLE workflow_definitions
    ADD CONSTRAINT workflow_definitions_state_check
        CHECK (state IS NULL OR state IN ('DRAFT', 'PUBLISHED'));

-- The unified model stores the statechart in `definition`; `yaml` is now optional
-- (deprecated, kept for existing automations until they migrate to a no-status definition).
ALTER TABLE workflow_definitions
    ALTER COLUMN yaml DROP NOT NULL;

-- Backfill existing YAML automations as published v1 definitions (no statuses).
-- definition stays NULL: the lifecycle resolver only reads definition-bearing rows,
-- so these continue to run on the YAML execution engine exactly as before.
UPDATE workflow_definitions
SET state   = 'PUBLISHED',
    version = 1
WHERE state IS NULL;
