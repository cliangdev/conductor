ALTER TABLE issues ADD COLUMN assignee_id VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX idx_issues_assignee ON issues(assignee_id);
