-- Add knowledge_ingest_interval_minutes to project_settings.
-- Controls how long KnowledgeIngestScheduler lets a lane accumulate before its first dispatch.
-- Existing rows default to 60 (hourly) -- the new product default -- rather than the prior
-- near-immediate (next 30s tick) behavior.
ALTER TABLE project_settings ADD COLUMN knowledge_ingest_interval_minutes INT NOT NULL DEFAULT 60;
