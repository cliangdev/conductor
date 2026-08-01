-- Project-scoped routing rules for the signal bus (see com.conductor.signal). A disposition_policy
-- row says "signals matching this glob should be treated as disposition X" -- KNOWLEDGE (file it into
-- the wiki), WORK_ITEM, NOTIFY, REFERENCE, or the escape hatch BLOCKED (veto). The table exists so a
-- project can steer routing WITHOUT a code change or redeploy -- e.g. "stop treating GSC digests as
-- knowledge-worthy" is a row edit, not a PR.
--
-- This is deliberately narrow in scope for its first migration: DispositionPolicySubscriber (see that
-- class's javadoc) gates only itself. The structural subscribers that already route signals in code
-- today (notification/workflow-automation/lifecycle/knowledge/pull-request-merge) read nothing from
-- this table and are never affected by it -- a BLOCKED row here can only veto whatever
-- DispositionPolicySubscriber itself would otherwise do, never silently disable an unrelated part of
-- the platform. An empty table is a complete no-op by construction.
--
-- signal_type is a glob (see com.conductor.signal.SignalGlob's segment-bounded grammar: '*' matches
-- one dot-separated segment, '**' matches one-or-more) so one row can cover a whole family of signal
-- types, e.g. "metrics.digest.**".
--
-- Every column participating in the UNIQUE constraint must be NOT NULL -- Postgres treats NULLs as
-- DISTINCT in a UNIQUE index, so a nullable signal_type or disposition would silently defeat
-- UNIQUE (project_id, signal_type, disposition) and let duplicate rows accumulate, same reasoning as
-- V104's connector_feed.ingest_id / V94's knowledge_domains.slug.
CREATE TABLE disposition_policy (
    id            VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id    VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    signal_type   VARCHAR(200) NOT NULL,
    disposition   VARCHAR(32) NOT NULL, -- KNOWLEDGE | WORK_ITEM | NOTIFY | REFERENCE | BLOCKED
    config        JSONB,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, signal_type, disposition)
);

CREATE INDEX idx_disposition_policy_project_enabled ON disposition_policy (project_id, enabled);
