-- Unified comments, expand step. `comments` (Work Item documents) is widened to also hold comments on
-- project docs and comments written by agents, replacing `doc_comments` / `doc_comment_replies` (V48, V109),
-- which stay in place as legacy tables until the contract migration drops them.
--
-- Everything here is additive or loosens a constraint, because preview deploys share the prod database
-- and the currently deployed code keeps running against this schema:
--   * the code that is live always fills the columns made nullable, and never knows the new ones;
--   * it reads `comments` by work_item_id, so it never sees the project-doc rows added later;
--   * the new CHECKs hold for every row the old code writes (a Work Item comment has a work item, a
--     document and an author), and are added NOT VALID then VALIDATEd so no long lock is taken.

ALTER TABLE comments
    ALTER COLUMN work_item_id DROP NOT NULL,
    ALTER COLUMN document_id DROP NOT NULL,
    ALTER COLUMN author_id DROP NOT NULL;

ALTER TABLE comments
    ADD COLUMN project_doc_id     VARCHAR(36) REFERENCES project_docs(id) ON DELETE CASCADE,
    ADD COLUMN author_label       TEXT,
    ADD COLUMN author_agent_id    VARCHAR(36) REFERENCES agents(id) ON DELETE SET NULL,
    ADD COLUMN edited_at          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN resolved_by_label  TEXT;

CREATE INDEX idx_comments_project_doc_id ON comments (project_doc_id);

ALTER TABLE comments
    ADD CONSTRAINT chk_comments_author
        CHECK (author_id IS NOT NULL OR author_label IS NOT NULL) NOT VALID,
    ADD CONSTRAINT chk_comments_one_target
        CHECK ((document_id IS NOT NULL AND work_item_id IS NOT NULL AND project_doc_id IS NULL)
            OR (project_doc_id IS NOT NULL AND document_id IS NULL AND work_item_id IS NULL)) NOT VALID;

ALTER TABLE comments VALIDATE CONSTRAINT chk_comments_author;
ALTER TABLE comments VALIDATE CONSTRAINT chk_comments_one_target;

ALTER TABLE comment_replies
    ALTER COLUMN author_id DROP NOT NULL;

ALTER TABLE comment_replies
    ADD COLUMN author_label     TEXT,
    ADD COLUMN author_agent_id  VARCHAR(36) REFERENCES agents(id) ON DELETE SET NULL,
    ADD COLUMN edited_at        TIMESTAMP WITH TIME ZONE;

ALTER TABLE comment_replies
    ADD CONSTRAINT chk_comment_replies_author
        CHECK (author_id IS NOT NULL OR author_label IS NOT NULL) NOT VALID;

ALTER TABLE comment_replies VALIDATE CONSTRAINT chk_comment_replies_author;
