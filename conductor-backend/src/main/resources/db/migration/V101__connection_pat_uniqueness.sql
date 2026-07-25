-- At most one ACTIVE PAT-type connection per (project, connector). Kept generic (not
-- github-specific, unlike uq_connection_github_installation_per_project in V56) so any future
-- connector gains the same one-active-PAT-per-project guarantee for free.
--
-- Rebinding a PAT (GitHubAppController#bindGithubPat) updates the existing ACTIVE row in place
-- rather than inserting a second one, so normal rotation never hits this index — it only guards
-- against a genuine double-bind race.
CREATE UNIQUE INDEX uq_connection_pat_per_project_connector
    ON connection (project_id, connector_id)
    WHERE auth_type = 'PAT' AND status = 'ACTIVE';
