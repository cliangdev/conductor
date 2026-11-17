-- Suggested edits (docs/comments.md "Suggested edits"): a QUOTE comment may carry replacement text for its quote.
-- suggestion_status is NULL for an ordinary comment. All columns nullable, so the previous release (which
-- neither reads nor writes them) keeps working. decided_at is TIMESTAMP like resolved_at on this table.
ALTER TABLE comments
    ADD COLUMN suggested_text              TEXT NULL,
    ADD COLUMN suggestion_status           TEXT NULL,
    ADD COLUMN suggestion_decided_by       VARCHAR(36) NULL REFERENCES users (id),
    ADD COLUMN suggestion_decided_by_label TEXT NULL,
    ADD COLUMN suggestion_decided_at       TIMESTAMP NULL;
ALTER TABLE comments ADD CONSTRAINT comments_suggestion_status_check
    CHECK (suggestion_status IS NULL OR suggestion_status IN ('PENDING', 'ACCEPTED', 'REJECTED'));
ALTER TABLE comments ADD CONSTRAINT comments_suggestion_shape_check
    CHECK ((suggestion_status IS NULL) = (suggested_text IS NULL));
