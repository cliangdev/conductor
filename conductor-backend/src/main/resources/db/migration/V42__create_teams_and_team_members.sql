CREATE TABLE teams (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    org_id VARCHAR(36) NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    created_by VARCHAR(36) REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, name)
);

CREATE INDEX idx_teams_org_id ON teams(org_id);

CREATE TABLE team_members (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    team_id VARCHAR(36) NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('LEAD', 'MEMBER')),
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (team_id, user_id)
);

CREATE INDEX idx_team_members_team_id ON team_members(team_id);
CREATE INDEX idx_team_members_user_id ON team_members(user_id);

ALTER TABLE projects
    ADD COLUMN team_id VARCHAR(36) REFERENCES teams(id) ON DELETE SET NULL;
