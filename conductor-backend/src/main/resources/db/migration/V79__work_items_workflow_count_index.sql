-- Back the sidebar/lifecycle Work Item COUNT queries with an index they can actually use.
--
-- WorkItemRepository.countByWorkflowSlug / countGroupedByWorkflowSlug / countByWorkflowSlugAndVersion
-- filter on `workflow` (and sometimes `workflow_version`) WITHOUT a project_id predicate. The existing
-- idx_work_items_project_workflow is keyed (project_id, workflow), so `workflow` is a non-leading column
-- and Postgres cannot use it for these workflow-scoped counts — they degrade to a seq scan on every
-- sidebar render and on the lifecycle version delete-guard check.
--
-- A composite on (workflow, workflow_version) serves both the slug-only counts (leading column) and the
-- slug+version count. Partial on workflow IS NOT NULL: unbound rows never match these predicates.
CREATE INDEX IF NOT EXISTS idx_work_items_workflow_version
    ON work_items(workflow, workflow_version)
    WHERE workflow IS NOT NULL;
