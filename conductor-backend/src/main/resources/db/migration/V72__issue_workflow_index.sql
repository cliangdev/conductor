-- Support per-Workflow Work Item views: GET /issues?workflow=ENGINEERING filters by the bound Workflow.
-- Sibling to V69's idx_issues_project_current_status.
CREATE INDEX IF NOT EXISTS idx_issues_project_workflow ON issues(project_id, workflow);
