-- HTML documents: comments anchor to a text selection (W3C TextQuoteSelector) instead of a markdown line.
-- A document's content hash lets a comment report `outdated` once the page changed after it was written.
-- Additive and nullable only (preview deploys share the prod DB). V165 is taken by the
-- reconnect-for-new-scopes PR.

ALTER TABLE documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

UPDATE documents
SET content_hash = encode(sha256(convert_to(content, 'UTF8')), 'hex')
WHERE content IS NOT NULL AND content_hash IS NULL;

ALTER TABLE comments ADD COLUMN IF NOT EXISTS anchor_exact TEXT;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS anchor_prefix TEXT;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS anchor_suffix TEXT;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS anchor_section_id VARCHAR(255);
ALTER TABLE comments ADD COLUMN IF NOT EXISTS anchor_section_title TEXT;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS document_hash VARCHAR(64);
