-- Backfill project_repositories from project_settings rows that have a GitHub config.
-- Parses repo_full_name as "<owner>/<repo>" from the github_repo_url.
-- Does NOT drop or alter github_repo_url / github_webhook_secret on project_settings.
INSERT INTO project_repositories (id, project_id, label, repo_url, repo_full_name, webhook_secret, connected_at)
SELECT
    gen_random_uuid()::text,
    ps.project_id,
    'Default',
    ps.github_repo_url,
    -- Extract "owner/repo" from a URL of the form https://github.com/owner/repo[.git]
    -- 1. Strip trailing .git if present
    -- 2. Take the substring after the second-to-last '/'
    REGEXP_REPLACE(
        REGEXP_REPLACE(ps.github_repo_url, '\.git$', ''),
        '^.*/([^/]+/[^/]+)$',
        '\1'
    ),
    ps.github_webhook_secret,
    NOW()
FROM project_settings ps
WHERE ps.github_repo_url IS NOT NULL
  AND ps.github_webhook_secret IS NOT NULL;
