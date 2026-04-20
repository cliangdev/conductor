CREATE TABLE org_invites (
    id VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    org_id VARCHAR(36) NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    invited_by VARCHAR(36) NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX org_invites_org_email_pending
    ON org_invites (org_id, email)
    WHERE status = 'PENDING';
