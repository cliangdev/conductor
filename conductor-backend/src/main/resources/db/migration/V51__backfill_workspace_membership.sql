-- V51: Backfill workspace (project) membership and invites from the org layer,
-- ahead of dropping the org layer in V52. Additive and re-runnable: every insert
-- uses ON CONFLICT / NOT EXISTS so existing (richer) project_members survive.
--
-- BEFORE RUNNING ON PROD, eyeball:
--   * Orgs with >1 project: org members fan out to ALL of the org's projects.
--       SELECT org_id, count(*) FROM projects WHERE org_id IS NOT NULL
--       GROUP BY org_id HAVING count(*) > 1;
--   * Org role MEMBER is mapped to project role CREATOR (write access) -- intended.
--   * Pending org invites are attached to the org's earliest-created project only.

-- 1. Orgs with NO project yet: create one default workspace per org, owned by the
--    org's earliest ADMIN (fallback: earliest member). Member-less orgs are skipped.
--    Key = up to 6 chars derived from the org name + '-' + a batch counter. The hyphen
--    guarantees no collision with existing (hyphen-free, name-derived) keys, and the
--    counter guarantees uniqueness within the batch, all within the VARCHAR(10) limit.
--    The creator is inserted as the workspace ADMIN in the same statement.
WITH orphan AS (
    SELECT o.id AS org_id, o.name AS org_name,
           (SELECT om.user_id FROM org_members om
            WHERE om.org_id = o.id
            ORDER BY (om.role <> 'ADMIN'), om.joined_at, om.id
            LIMIT 1) AS owner_user_id
    FROM organizations o
    WHERE NOT EXISTS (SELECT 1 FROM projects p WHERE p.org_id = o.id)
),
keyed AS (
    SELECT org_id, org_name, owner_user_id,
           UPPER(CASE WHEN org_name ~ '\s'
                  THEN REGEXP_REPLACE(org_name, '(?:^|\s+)(\S)\S*', '\1', 'g')
                  ELSE LEFT(REGEXP_REPLACE(org_name, '[^A-Za-z0-9]', '', 'g'), 4)
                 END) AS base,
           ROW_NUMBER() OVER (ORDER BY org_id) AS gid
    FROM orphan
    WHERE owner_user_id IS NOT NULL
),
ins AS (
    INSERT INTO projects (name, key, created_by, org_id)
    SELECT LEFT(org_name, 100),
           LEFT(COALESCE(NULLIF(base, ''), 'WS'), 6) || '-' || gid::text,
           owner_user_id,
           org_id
    FROM keyed
    RETURNING id, created_by
)
INSERT INTO project_members (project_id, user_id, role, joined_at)
SELECT id, created_by, 'ADMIN'::member_role, NOW()
FROM ins
ON CONFLICT (project_id, user_id) DO NOTHING;

-- 2. Every org member becomes a member of every project in their org.
--    ADMIN -> ADMIN, MEMBER -> CREATOR. ON CONFLICT keeps any existing (richer)
--    project role, including the workspace owners seeded above.
INSERT INTO project_members (project_id, user_id, role, joined_at)
SELECT p.id, om.user_id,
       (CASE om.role WHEN 'ADMIN' THEN 'ADMIN' ELSE 'CREATOR' END)::member_role,
       NOW()
FROM org_members om
JOIN projects p ON p.org_id = om.org_id
ON CONFLICT (project_id, user_id) DO NOTHING;

-- 3. Pending org invites become pending project invites on the org's earliest-created
--    project. The conflict target matches the partial unique index so a duplicate
--    pending (project, email) is skipped rather than erroring; the NOT EXISTS guard on
--    token avoids any collision with the global token unique and makes the step re-runnable.
INSERT INTO invites (project_id, email, role, token, invited_by, status, expires_at, created_at)
SELECT prim.project_id, oi.email,
       (CASE oi.role WHEN 'ADMIN' THEN 'ADMIN' ELSE 'CREATOR' END)::member_role,
       oi.token, oi.invited_by, 'PENDING', oi.expires_at, oi.created_at
FROM org_invites oi
JOIN LATERAL (
    SELECT p.id AS project_id FROM projects p
    WHERE p.org_id = oi.org_id
    ORDER BY p.created_at, p.id
    LIMIT 1
) prim ON true
WHERE oi.status = 'PENDING'
  AND NOT EXISTS (SELECT 1 FROM invites i WHERE i.token = oi.token)
ON CONFLICT (project_id, email) WHERE status = 'PENDING' DO NOTHING;
