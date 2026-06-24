-- COND-18 E5: Assets — first-class produced outputs on a Work Item (PR, published URL, file).
-- Distinct from Documents (intent): an Asset is what a Step deposits and what metrics measure.
-- Supersedes the ad-hoc issues.github_pr_url column (kept for one release; backfilled separately).

CREATE TABLE assets (
    id         VARCHAR(36) PRIMARY KEY,
    issue_id   VARCHAR(36) NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    type       VARCHAR(64) NOT NULL,
    label      VARCHAR(255),
    kind       VARCHAR(16) NOT NULL,
    ref        TEXT        NOT NULL,
    done       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT assets_kind_check CHECK (kind IN ('link', 'file'))
);

CREATE INDEX idx_assets_issue_id ON assets(issue_id);
