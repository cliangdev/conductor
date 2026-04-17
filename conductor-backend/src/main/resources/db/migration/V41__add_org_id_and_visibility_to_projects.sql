ALTER TABLE projects
    ADD COLUMN org_id VARCHAR(36) REFERENCES organizations(id) ON DELETE SET NULL;

ALTER TABLE projects
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'ORG'
        CHECK (visibility IN ('PRIVATE', 'ORG', 'TEAM', 'PUBLIC'));

CREATE INDEX idx_projects_org_id ON projects(org_id);
