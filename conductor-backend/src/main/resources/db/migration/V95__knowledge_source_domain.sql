-- Stamps each knowledge_sources row with the domain lane (KnowledgeDomainResolver) it was routed to at
-- submit time -- null means the generalist/unclassified lane. Existing rows are left null (unchanged);
-- they simply keep draining through the null lane, never backfilled or stranded.
ALTER TABLE knowledge_sources ADD COLUMN domain VARCHAR(64);

CREATE INDEX idx_knowledge_sources_project_status_domain ON knowledge_sources (project_id, status, domain);
