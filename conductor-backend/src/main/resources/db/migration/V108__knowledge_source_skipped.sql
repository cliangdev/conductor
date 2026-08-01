-- SKIPPED is a new knowledge_sources.status value (read/inspected by the librarian, then judged not
-- worth filing) -- distinct from PROCESSED (filed) and DEAD (never got a verdict). The status column
-- has no CHECK constraint, so the new enum value itself needs no DDL; skip_reason is the librarian's
-- free-text explanation for why the source wasn't filed, mirroring error_message's shape.
ALTER TABLE knowledge_sources ADD COLUMN skip_reason TEXT;

-- Drains rows filed under the retired per-status-change ingestion (conductor.work_item.status_changed
-- knowledge sourceType -- not the identically-named Signal type, which is unchanged and still fires;
-- only what KnowledgeSignalSink used to file under that name is retired). Without this, deploy day would
-- let the scheduler pick up and file exactly one last batch of the bare-scalar junk this change exists
-- to stop -- the code path that filed them is already gone by the time this migration runs, so these
-- rows would otherwise sit PENDING forever.
--
-- Scoped to PENDING only:
--   * PROCESSING is left untouched -- a librarian run already has those ids checked out, and flipping
--     their status out from under it would make markProcessed's WHERE status IN (PENDING, PROCESSING)
--     guard match zero rows when that run lands, orphaning its provenance. At most one in-flight batch;
--     letting it finish is cheaper than racing it.
--   * PROCESSED/DEAD are history referenced by knowledge_revision_sources and must never be rewritten.
UPDATE knowledge_sources SET status = 'SKIPPED',
       skip_reason = 'Superseded by conductor.work_item.completed ingestion (V108) -- '
                  || 'per-status-change sources are no longer filed.'
 WHERE source_type = 'conductor.work_item.status_changed' AND status = 'PENDING';
