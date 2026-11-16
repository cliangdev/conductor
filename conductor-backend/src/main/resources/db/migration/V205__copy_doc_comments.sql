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

-- The mirror is deliberately not a full upsert. A row copied once belongs to the new code from then on: an
-- UPDATE from the old release changes only the columns it actually changed (so its stale-flag sweep cannot
-- reopen a thread that was resolved in the new code), and never inserts (so a row the new code deleted is not
-- resurrected by a late write of the old release). Only an INSERT creates a unified row, and only if absent.
CREATE OR REPLACE FUNCTION mirror_doc_comment() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM comments WHERE id = OLD.id;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        INSERT INTO comments (id, work_item_id, document_id, project_doc_id, author_id, author_label, content,
                              line_number, quoted_text, line_stale, resolved_at, resolved_by, resolved_by_label,
                              created_at, updated_at)
        VALUES (NEW.id, NULL, NULL, NEW.doc_id, NEW.author_id, NEW.author_label, NEW.content,
                COALESCE(NEW.line_number, 0), COALESCE(NEW.quoted_text, ''), NEW.line_stale,
                (NEW.resolved_at AT TIME ZONE 'UTC'), NEW.resolved_by, NEW.resolved_by_label,
                (NEW.created_at AT TIME ZONE 'UTC'), (NEW.updated_at AT TIME ZONE 'UTC'))
        ON CONFLICT (id) DO NOTHING;
        RETURN NEW;
    END IF;
    UPDATE comments SET
        resolved_at       = CASE WHEN NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
                                 THEN (NEW.resolved_at AT TIME ZONE 'UTC') ELSE resolved_at END,
        resolved_by       = CASE WHEN NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
                                 THEN NEW.resolved_by ELSE resolved_by END,
        resolved_by_label = CASE WHEN NEW.resolved_by_label IS DISTINCT FROM OLD.resolved_by_label
                                 THEN NEW.resolved_by_label ELSE resolved_by_label END,
        line_stale        = CASE WHEN NEW.line_stale IS DISTINCT FROM OLD.line_stale
                                 THEN NEW.line_stale ELSE line_stale END,
        content           = CASE WHEN NEW.content IS DISTINCT FROM OLD.content
                                 THEN NEW.content ELSE content END,
        line_number       = CASE WHEN NEW.line_number IS DISTINCT FROM OLD.line_number
                                 THEN COALESCE(NEW.line_number, 0) ELSE line_number END,
        quoted_text       = CASE WHEN NEW.quoted_text IS DISTINCT FROM OLD.quoted_text
                                 THEN COALESCE(NEW.quoted_text, '') ELSE quoted_text END,
        updated_at        = CASE WHEN NEW.updated_at IS DISTINCT FROM OLD.updated_at
                                 THEN (NEW.updated_at AT TIME ZONE 'UTC') ELSE updated_at END
    WHERE id = NEW.id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION mirror_doc_comment_reply() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM comment_replies WHERE id = OLD.id;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        INSERT INTO comment_replies (id, comment_id, author_id, author_label, content, created_at)
        VALUES (NEW.id, NEW.comment_id, NEW.author_id, NEW.author_label, NEW.content,
                (NEW.created_at AT TIME ZONE 'UTC'))
        ON CONFLICT (id) DO NOTHING;
        RETURN NEW;
    END IF;
    UPDATE comment_replies SET
        content = CASE WHEN NEW.content IS DISTINCT FROM OLD.content THEN NEW.content ELSE content END
    WHERE id = NEW.id;
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
