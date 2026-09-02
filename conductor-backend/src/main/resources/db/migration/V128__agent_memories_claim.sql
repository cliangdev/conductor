-- consolidation_claimed_at: a claim marker stamped by MemoryConsolidationService's per-batch claim step
-- (FOR UPDATE SKIP LOCKED) so concurrent MemoryMaintenanceScheduler instances -- and successive batches
-- drained from the same project within one scheduler tick -- never re-fetch and re-bill the same RAW
-- rows. A claim stamped by an instance that then crashes mid-tick becomes reclaimable again once it's
-- older than the service's stale-claim window, rather than wedging those rows forever.

ALTER TABLE agent_memories ADD COLUMN consolidation_claimed_at TIMESTAMPTZ NULL;

ALTER TABLE agent_memories ADD CONSTRAINT ck_agent_memories_type
    CHECK (memory_type IN ('FACT', 'DECISION', 'PREFERENCE', 'EVENT'));
ALTER TABLE agent_memories ADD CONSTRAINT ck_agent_memories_status
    CHECK (status IN ('RAW', 'ACTIVE'));
ALTER TABLE agent_memories ADD CONSTRAINT ck_agent_memories_importance
    CHECK (importance BETWEEN 1 AND 10);
