-- Unified comments, contract step. V205 copied doc_comments / doc_comment_replies into comments /
-- comment_replies and mirrored later legacy writes with triggers; the code no longer reads or writes the
-- legacy tables, so the triggers, their functions and the tables go.
--
-- Safety net first: carry over any legacy row written since V205 that has no unified twin (a trigger that
-- missed it), using the V205 column mapping and UTC casts. Only rows created after V205 was applied qualify:
-- an older legacy row without a twin was copied by V205 and then deleted by the new code (which never
-- touches the legacy tables), and copying it again would resurrect a deleted comment. Idempotent:
-- NOT EXISTS plus ON CONFLICT, and a no-op once the tables are gone.

DO $$
DECLARE
    v205 TIMESTAMPTZ;
BEGIN
    IF to_regclass('doc_comments') IS NULL THEN
        RETURN;
    END IF;

    SELECT COALESCE(max(installed_on)::timestamptz, '-infinity'::timestamptz)
      INTO v205
      FROM flyway_schema_history
     WHERE version = '205';

    INSERT INTO comments (id, work_item_id, document_id, project_doc_id, author_id, author_label, content,
                          line_number, quoted_text, line_stale, resolved_at, resolved_by, resolved_by_label,
                          created_at, updated_at)
    SELECT d.id, NULL, NULL, d.doc_id, d.author_id, d.author_label, d.content,
           COALESCE(d.line_number, 0), COALESCE(d.quoted_text, ''), d.line_stale,
           (d.resolved_at AT TIME ZONE 'UTC'), d.resolved_by, d.resolved_by_label,
           (d.created_at AT TIME ZONE 'UTC'), (d.updated_at AT TIME ZONE 'UTC')
      FROM doc_comments d
     WHERE d.created_at > v205
       AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = d.id)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO comment_replies (id, comment_id, author_id, author_label, content, created_at)
    SELECT r.id, r.comment_id, r.author_id, r.author_label, r.content, (r.created_at AT TIME ZONE 'UTC')
      FROM doc_comment_replies r
     WHERE r.created_at > v205
       AND EXISTS (SELECT 1 FROM comments c WHERE c.id = r.comment_id)
       AND NOT EXISTS (SELECT 1 FROM comment_replies x WHERE x.id = r.id)
    ON CONFLICT (id) DO NOTHING;
END
$$;

DROP TRIGGER IF EXISTS trg_mirror_doc_comment_reply ON doc_comment_replies;
DROP TRIGGER IF EXISTS trg_mirror_doc_comment ON doc_comments;
DROP FUNCTION IF EXISTS mirror_doc_comment_reply();
DROP FUNCTION IF EXISTS mirror_doc_comment();

DROP TABLE IF EXISTS doc_comment_replies;
DROP TABLE IF EXISTS doc_comments;
