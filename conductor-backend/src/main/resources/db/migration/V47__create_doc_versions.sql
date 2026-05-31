CREATE TABLE doc_versions (
    id             VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    doc_id         VARCHAR(36) NOT NULL REFERENCES project_docs(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    content        TEXT NOT NULL,
    author_id      VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (doc_id, version_number)
);

CREATE INDEX idx_doc_versions_doc_id ON doc_versions (doc_id);
