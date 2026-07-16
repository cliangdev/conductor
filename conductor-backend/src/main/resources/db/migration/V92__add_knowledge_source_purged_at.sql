-- Retention for the ingestion inbox (Phase 4): once a source's payload has served its purpose (filed
-- into wiki pages, or dead-lettered permanently), the raw payload no longer needs to stick around.
-- KnowledgeRetentionService compacts PROCESSED sources' payload (nulling the inline column and/or
-- deleting the offloaded GCS object) after they've sat around for a while, and hard-deletes DEAD
-- sources once they're old enough that retrying them is no longer meaningful. purged_at marks the
-- moment a row's payload was compacted -- independent of a DEAD row's later hard deletion.
ALTER TABLE knowledge_sources ADD COLUMN purged_at TIMESTAMP WITH TIME ZONE NULL;

-- Supports KnowledgeRetentionService's two sweep queries: PROCESSED rows past the compaction age
-- (received_at, status, purged_at IS NULL) and DEAD rows past the hard-delete age (received_at, status).
CREATE INDEX idx_knowledge_sources_retention ON knowledge_sources (status, received_at);
