CREATE TABLE comments (
    id               VARCHAR(36) PRIMARY KEY,
    issue_id         VARCHAR(36) NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    document_id      VARCHAR(36) NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    author_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    content          TEXT NOT NULL,
    line_number      INTEGER,
    selection_start  INTEGER,
    selection_length INTEGER,
    resolved_at      TIMESTAMP,
    resolved_by      VARCHAR(36) REFERENCES users(id),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_comment_anchor CHECK (
        line_number IS NOT NULL OR
        (selection_start IS NOT NULL AND selection_length IS NOT NULL)
    )
);

CREATE TABLE comment_replies (
    id          VARCHAR(36) PRIMARY KEY,
    comment_id  VARCHAR(36) NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    author_id   VARCHAR(36) NOT NULL REFERENCES users(id),
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
