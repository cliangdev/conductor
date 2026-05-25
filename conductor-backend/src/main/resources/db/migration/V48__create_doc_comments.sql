CREATE TABLE doc_comments (
    id          VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    doc_id      VARCHAR(36) NOT NULL REFERENCES project_docs(id) ON DELETE CASCADE,
    author_id   VARCHAR(36) NOT NULL REFERENCES users(id),
    content     TEXT NOT NULL,
    line_number INTEGER,
    quoted_text TEXT,
    line_stale  BOOLEAN NOT NULL DEFAULT false,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by VARCHAR(36) REFERENCES users(id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_comments_doc_id ON doc_comments (doc_id);

CREATE TABLE doc_comment_replies (
    id         VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    comment_id VARCHAR(36) NOT NULL REFERENCES doc_comments(id) ON DELETE CASCADE,
    author_id  VARCHAR(36) NOT NULL REFERENCES users(id),
    content    TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_comment_replies_comment_id ON doc_comment_replies (comment_id);
