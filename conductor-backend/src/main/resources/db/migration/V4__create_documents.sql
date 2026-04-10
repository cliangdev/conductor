CREATE TABLE documents (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    issue_id VARCHAR(36) NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL DEFAULT 'text/markdown',
    content TEXT,
    storage_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (issue_id, filename)
);
