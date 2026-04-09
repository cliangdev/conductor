CREATE TYPE issue_type AS ENUM ('PRD', 'FEATURE_REQUEST', 'BUG_REPORT');
CREATE TYPE issue_status AS ENUM ('DRAFT', 'IN_REVIEW', 'APPROVED', 'CHANGES_REQUESTED', 'ARCHIVED');

CREATE TABLE issues (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    type issue_type NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status issue_status NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(36) NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_issues_project_id ON issues(project_id);
CREATE INDEX idx_issues_project_status ON issues(project_id, status);
