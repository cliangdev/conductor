-- SKIPPED is a new knowledge_sources.status value (read/inspected by the librarian, then judged not
-- worth filing) -- distinct from PROCESSED (filed) and DEAD (never got a verdict). The status column
-- has no CHECK constraint, so the new enum value itself needs no DDL; skip_reason is the librarian's
-- free-text explanation for why the source wasn't filed, mirroring error_message's shape.
ALTER TABLE knowledge_sources ADD COLUMN skip_reason TEXT;
