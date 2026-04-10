CREATE TABLE issue_reviewers (
    id           VARCHAR(36) PRIMARY KEY,
    issue_id     VARCHAR(36) NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    user_id      VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    assigned_by  VARCHAR(36) NOT NULL REFERENCES users(id),
    CONSTRAINT uq_issue_reviewer UNIQUE (issue_id, user_id)
);
