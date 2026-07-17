-- Per-project opt-in for the Knowledge Center (Phase 3: ingestion pipeline). Defaults to false so
-- existing projects don't suddenly start provisioning the knowledge-librarian/knowledge-bootstrap
-- system workflows or accepting ingestion sources until an ADMIN explicitly turns it on.
ALTER TABLE project_settings ADD COLUMN knowledge_enabled BOOLEAN NOT NULL DEFAULT false;
