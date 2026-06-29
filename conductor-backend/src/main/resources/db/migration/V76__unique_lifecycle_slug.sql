-- A lifecycle workflow's identity is its statechart slug (definition.id), not its human label.
-- Enforce one workflow per (project, slug) at the DB level. YAML automations have a NULL definition
-- and are excluded via the partial predicate. Seeding is idempotent on slug, so no existing dupes
-- are expected; if this index build fails, dedupe colliding rows first.
CREATE UNIQUE INDEX idx_workflow_definitions_project_slug
    ON workflow_definitions (project_id, (definition ->> 'id'))
    WHERE definition IS NOT NULL;
