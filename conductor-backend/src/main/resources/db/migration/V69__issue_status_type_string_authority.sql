-- Wave 1: flip Work Item status/type authority from the native PG enums to
-- Workflow-validated strings, so a custom Workflow's statuses/types can be stored.
--
-- The string columns become authoritative; the legacy enum columns (status,
-- type of pg types issue_status/issue_type) are relaxed to NULLABLE and kept for
-- one release for rolling-deploy safety and rollback. They are dropped (with the
-- pg enum types) in a follow-up migration once the old revisions retire.

-- 1. current_status (VARCHAR) becomes the status authority.
UPDATE issues SET current_status = status::text WHERE current_status IS NULL;
ALTER TABLE issues ALTER COLUMN current_status SET NOT NULL;
ALTER TABLE issues ALTER COLUMN status DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_issues_project_current_status ON issues(project_id, current_status);

-- 2. item_type (VARCHAR) becomes the type authority, validated against the bound Workflow's `types`.
--    Kept NULLABLE this release for rolling-deploy / rollback safety: the previous revision's entity has no
--    item_type field, so its INSERTs omit the column — a NOT NULL here (with no default) would fail every
--    issue-creation on the old/rolled-back revision during the deploy overlap. New code always sets it; the
--    NOT NULL is added in the follow-up migration that drops the legacy enum columns, after old pods retire.
ALTER TABLE issues ADD COLUMN item_type VARCHAR(64);
UPDATE issues SET item_type = type::text WHERE item_type IS NULL;
ALTER TABLE issues ALTER COLUMN type DROP NOT NULL;

-- 3. Every Work Item is Workflow-bound (no more nullable binding).
UPDATE issues SET workflow = 'ENGINEERING' WHERE workflow IS NULL;
UPDATE issues SET workflow_version = 1 WHERE workflow_version IS NULL;
ALTER TABLE issues ALTER COLUMN workflow SET NOT NULL;
ALTER TABLE issues ALTER COLUMN workflow_version SET NOT NULL;
