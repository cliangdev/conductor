CREATE TABLE reviews (
    id           VARCHAR(36) PRIMARY KEY,
    issue_id     VARCHAR(36) NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    reviewer_id  VARCHAR(36) NOT NULL REFERENCES users(id),
    verdict      VARCHAR(32) NOT NULL CHECK (verdict IN ('APPROVED','CHANGES_REQUESTED','COMMENTED')),
    body         TEXT,
    submitted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review UNIQUE (issue_id, reviewer_id)
);

CREATE TABLE project_settings (
    id                  VARCHAR(36) PRIMARY KEY,
    project_id          VARCHAR(36) NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    discord_webhook_url VARCHAR(512),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
