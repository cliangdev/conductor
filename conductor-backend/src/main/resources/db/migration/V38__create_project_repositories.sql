CREATE TABLE project_repositories (
    id              VARCHAR(36)  NOT NULL,
    project_id      VARCHAR(36)  NOT NULL,
    label           VARCHAR(100) NOT NULL,
    repo_url        VARCHAR(512) NOT NULL,
    repo_full_name  VARCHAR(255) NOT NULL,
    webhook_secret  VARCHAR(255) NOT NULL,
    connected_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    connected_by    VARCHAR(36),
    PRIMARY KEY (id),
    CONSTRAINT fk_project_repositories_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_repositories_user FOREIGN KEY (connected_by) REFERENCES users(id) ON DELETE SET NULL
);
