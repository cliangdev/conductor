-- V52: Drop the org/team layer. A Project is now the single top-level "Workspace"
-- entity and project_members is the only access gate (see V51 for the backfill).
--
-- Must deploy together with the matching entity change (Project no longer maps
-- org_id/team_id/visibility) so Hibernate schema validation passes on boot.
--
-- FK-safe order: drop the project columns (and their FKs) before the tables they
-- reference; drop child tables before their parents.

DROP INDEX IF EXISTS idx_projects_org_id;

ALTER TABLE projects DROP COLUMN IF EXISTS team_id;
ALTER TABLE projects DROP COLUMN IF EXISTS org_id;
ALTER TABLE projects DROP COLUMN IF EXISTS visibility;

DROP TABLE IF EXISTS org_invites;
DROP TABLE IF EXISTS team_members;
DROP TABLE IF EXISTS teams;
DROP TABLE IF EXISTS org_members;
DROP TABLE IF EXISTS organizations;
