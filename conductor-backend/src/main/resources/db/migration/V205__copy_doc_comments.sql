-- Unified comments, copy step. Copies every project-doc comment and reply into the unified tables under the
-- same ids, then keeps `comments` / `comment_replies` in step with the legacy tables, one direction only:
-- while the previous release is still deployed (preview deploys share this database) it keeps writing
-- doc_comments, and those writes must show up in the unified tables. The new code writes `comments`
-- directly and never touches doc_comments, so nothing is mirrored twice. The triggers and the legacy
-- tables are dropped by the contract migration.
--
-- `comments.created_at` / `resolved_at` / `updated_at` and `comment_replies.created_at` are TIMESTAMP
-- (no zone) holding UTC wall-clock (the app runs in UTC; Hibernate binds OffsetDateTime with the session
-- zone, which is the JVM default); the legacy tables are TIMESTAMPTZ, hence AT TIME ZONE 'UTC', which
-- does not depend on the session's TimeZone. `line_number` and `quoted_text` are
-- NOT NULL there (V35), hence the coalesces: 0 / '' mean "no line" / "no quote".

CREATE OR REPLACE FUNCTION mirror_doc_comment() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM comments WHERE id = OLD.id;
        RETURN OLD;
    END IF;
    INSERT INTO comments (id, work_item_id, document_id, project_doc_id, author_id, author_label, content,
                          line_number, quoted_text, line_stale, resolved_at, resolved_by, resolved_by_label,
                          created_at, updated_at)
    VALUES (NEW.id, NULL, NULL, NEW.doc_id, NEW.author_id, NEW.author_label, NEW.content,
            COALESCE(NEW.line_number, 0), COALESCE(NEW.quoted_text, ''), NEW.line_stale,
            (NEW.resolved_at AT TIME ZONE 'UTC'), NEW.resolved_by, NEW.resolved_by_label,
            (NEW.created_at AT TIME ZONE 'UTC'), (NEW.updated_at AT TIME ZONE 'UTC'))
    ON CONFLICT (id) DO UPDATE SET
        project_doc_id    = EXCLUDED.project_doc_id,
        author_id         = EXCLUDED.author_id,
        author_label      = EXCLUDED.author_label,
        content           = EXCLUDED.content,
        line_number       = EXCLUDED.line_number,
        quoted_text       = EXCLUDED.quoted_text,
        line_stale        = EXCLUDED.line_stale,
        resolved_at       = EXCLUDED.resolved_at,
        resolved_by       = EXCLUDED.resolved_by,
        resolved_by_label = EXCLUDED.resolved_by_label,
        updated_at        = EXCLUDED.updated_at;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION mirror_doc_comment_reply() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM comment_replies WHERE id = OLD.id;
        RETURN OLD;
    END IF;
    INSERT INTO comment_replies (id, comment_id, author_id, author_label, content, created_at)
    VALUES (NEW.id, NEW.comment_id, NEW.author_id, NEW.author_label, NEW.content, (NEW.created_at AT TIME ZONE 'UTC'))
    ON CONFLICT (id) DO UPDATE SET
        author_id    = EXCLUDED.author_id,
        author_label = EXCLUDED.author_label,
        content      = EXCLUDED.content;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Initial copy.
INSERT INTO comments (id, work_item_id, document_id, project_doc_id, author_id, author_label, content,
                      line_number, quoted_text, line_stale, resolved_at, resolved_by, resolved_by_label,
                      created_at, updated_at)
SELECT id, NULL, NULL, doc_id, author_id, author_label, content,
       COALESCE(line_number, 0), COALESCE(quoted_text, ''), line_stale,
       (resolved_at AT TIME ZONE 'UTC'), resolved_by, resolved_by_label,
       (created_at AT TIME ZONE 'UTC'), (updated_at AT TIME ZONE 'UTC')
FROM doc_comments
ON CONFLICT (id) DO NOTHING;

INSERT INTO comment_replies (id, comment_id, author_id, author_label, content, created_at)
SELECT id, comment_id, author_id, author_label, content, (created_at AT TIME ZONE 'UTC')
FROM doc_comment_replies
ON CONFLICT (id) DO NOTHING;

CREATE TRIGGER trg_mirror_doc_comment
    AFTER INSERT OR UPDATE OR DELETE ON doc_comments
    FOR EACH ROW EXECUTE FUNCTION mirror_doc_comment();

CREATE TRIGGER trg_mirror_doc_comment_reply
    AFTER INSERT OR UPDATE OR DELETE ON doc_comment_replies
    FOR EACH ROW EXECUTE FUNCTION mirror_doc_comment_reply();
