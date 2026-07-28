-- Project docs were human-only: every author column was a NOT NULL FK to users, so a machine
-- principal (a project API key, or a run-scoped MCP token from a workflow container) could not
-- author a doc, a version, a comment or a reply at all.
--
-- Authorship becomes "either a user or a label": the FK goes nullable and a *_label column carries
-- the machine actor's identity (e.g. 'Agent (run a1b2c3d4)'). The CHECK constraints are what keep a
-- byline from ever rendering blank -- at least one form of attribution is always present. (Which of
-- the two a row uses is decided by ProjectActor, which never sets both.)

ALTER TABLE project_docs
    ALTER COLUMN created_by DROP NOT NULL,
    ALTER COLUMN updated_by DROP NOT NULL,
    ADD COLUMN created_by_label VARCHAR(255),
    ADD COLUMN updated_by_label VARCHAR(255),
    ADD CONSTRAINT chk_project_docs_created_attribution
        CHECK (created_by IS NOT NULL OR created_by_label IS NOT NULL),
    ADD CONSTRAINT chk_project_docs_updated_attribution
        CHECK (updated_by IS NOT NULL OR updated_by_label IS NOT NULL);

ALTER TABLE doc_versions
    ALTER COLUMN author_id DROP NOT NULL,
    ADD COLUMN author_label VARCHAR(255),
    ADD CONSTRAINT chk_doc_versions_attribution
        CHECK (author_id IS NOT NULL OR author_label IS NOT NULL);

-- resolved_by_label completes the pair for the one attribution that is optional either way: a thread
-- may be unresolved (both null), but once resolved it names whoever resolved it, agent or human.
ALTER TABLE doc_comments
    ALTER COLUMN author_id DROP NOT NULL,
    ADD COLUMN author_label VARCHAR(255),
    ADD COLUMN resolved_by_label VARCHAR(255),
    ADD CONSTRAINT chk_doc_comments_attribution
        CHECK (author_id IS NOT NULL OR author_label IS NOT NULL);

ALTER TABLE doc_comment_replies
    ALTER COLUMN author_id DROP NOT NULL,
    ADD COLUMN author_label VARCHAR(255),
    ADD CONSTRAINT chk_doc_comment_replies_attribution
        CHECK (author_id IS NOT NULL OR author_label IS NOT NULL);
