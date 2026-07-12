-- Cross-job artifact passing: a docker/claude-code step declares named file artifacts it produces;
-- downstream jobs (via `consumes:`) resolve them as pre-downloaded files or signed-URL interpolation
-- (needs.JOB.artifacts.NAME expressions). One row per declared artifact, created PENDING at
-- upload-URL issuance time and flipped to UPLOADED once the producing step confirms the upload
-- completed. NOTE: never write a literal dollar-brace interpolation placeholder in this file, even
-- in a comment -- Flyway's placeholder parser scans raw SQL text before it knows what's a comment,
-- and fails the whole migration with "No value provided for placeholder" if it sees one.
CREATE TABLE workflow_artifacts (
    id           VARCHAR(36) PRIMARY KEY,
    run_id       VARCHAR(36) NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    job_id       VARCHAR(100) NOT NULL,
    job_run_id   VARCHAR(36) REFERENCES workflow_job_runs(id) ON DELETE SET NULL,
    name         VARCHAR(160) NOT NULL,
    gcs_path     TEXT NOT NULL,
    size_bytes   BIGINT,
    content_type VARCHAR(120),
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, name)
);

CREATE INDEX idx_workflow_artifacts_run_id ON workflow_artifacts (run_id);
