ALTER TABLE project_settings
    ADD COLUMN github_webhook_secret VARCHAR(512),
    ADD COLUMN github_repo_url VARCHAR(512);
