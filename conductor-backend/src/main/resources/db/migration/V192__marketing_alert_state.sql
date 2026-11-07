-- What the marketing alerts have already said (RE-99 T6.2). Additive only.
--
-- marketing_alert_state  one row per (workspace, alert key): whether the condition holds now (ACTIVE) or held and
--                        has cleared (RESOLVED), and the fingerprint it was last posted with. The evaluator posts
--                        to Discord only when a key is new, comes back after RESOLVED, or its fingerprint has moved
--                        meaningfully, and posts one "Resolved" message when an ACTIVE key stops holding, so the
--                        same condition never posts twice. Rows are written even when the workspace has no channel
--                        to post to (last_posted_at stays NULL), so setting a channel up later does not replay
--                        every standing condition.
--
-- alert_key    mismatch:{channel}:{metric}:{kind} | stale:{channel} | stop_rule:{ruleId} | off_track:{channel}:{metric}
-- fingerprint  the figures that make the condition "the same", rounded (a mismatch's gap in percentage points, a
--              stop rule's observed value); a condition with no figures (stale) carries a fixed word. It is the
--              baseline the next evaluation compares against, so it moves only when a message goes out.
-- details      title, text, kind and channel of the last message, so a "Resolved" message can say what resolved.

CREATE TABLE marketing_alert_state (
    id             VARCHAR(36)  PRIMARY KEY,
    project_id     VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    alert_key      VARCHAR(200) NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    fingerprint    VARCHAR(200) NOT NULL,
    details        JSONB,
    first_seen_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_posted_at TIMESTAMP WITH TIME ZONE,
    resolved_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_marketing_alert_state UNIQUE (project_id, alert_key),
    CONSTRAINT ck_marketing_alert_state_state CHECK (state IN ('ACTIVE', 'RESOLVED'))
);

COMMENT ON TABLE marketing_alert_state IS
    'Per-workspace record of which marketing alerts are standing, so a condition posts once and resolves once.';
