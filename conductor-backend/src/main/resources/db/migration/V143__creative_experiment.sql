-- COND-24 T5: hook experiments. Two or more variants of a Creative, each published as its own Post,
-- decided by a winner rule once every variant has reported a performance snapshot at least
-- window_hours after it fired. See docs/creatives.md's "Performance and experiments" section and
-- CreativeExperimentService.

CREATE TABLE creative_experiment (
    id                        VARCHAR(36) PRIMARY KEY,
    project_id                VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- The family's root Creative (variant_letter = 'a', parent_creative_id NULL) — every letter under
    -- the same number is a candidate variant. Not a foreign key with ON DELETE CASCADE from `creative`
    -- because an experiment is a decision record that should outlive a Creative being archived.
    parent_creative_id        VARCHAR(36) NOT NULL REFERENCES creative(id),
    -- views | engagement_rate | avg_view_pct — the metric requested at creation; the decision prefers
    -- avg_view_pct whenever every variant reports it regardless of this column (see CreativeExperimentService).
    metric                    VARCHAR(24) NOT NULL DEFAULT 'views',
    window_hours              INT NOT NULL DEFAULT 72,
    state                     VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    winner_creative_id        VARCHAR(36) REFERENCES creative(id),
    decided_at                TIMESTAMP WITH TIME ZONE,
    -- Per-variant numbers used to decide (or why a decision could not be made yet) — see
    -- CreativeExperimentService#buildSummary. Never re-derived after decidedAt is set.
    summary                   JSONB,
    -- Set only by POST .../experiments/{id}/confirm-winner — a human action, never automatic (no
    -- silent brand drift). Null until then, and set at most once.
    winner_line_confirmed_at  TIMESTAMP WITH TIME ZONE,
    winner_line_confirmed_by  VARCHAR(36) REFERENCES users(id),
    created_by                VARCHAR(36) REFERENCES users(id),
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_creative_experiment_project ON creative_experiment (project_id);
CREATE INDEX idx_creative_experiment_parent ON creative_experiment (parent_creative_id);

-- One RUNNING experiment per family at a time (CreativeExperimentService#create, 409 on a second attempt).
CREATE UNIQUE INDEX uq_creative_experiment_running_per_family
    ON creative_experiment (parent_creative_id)
    WHERE state = 'RUNNING';

-- The weekly "what works" digest job's "Hook winners" section: experiments decided since the last run.
CREATE INDEX idx_creative_experiment_decided ON creative_experiment (project_id, state, decided_at);

COMMENT ON TABLE creative_experiment IS
    'A hook experiment (COND-24 T5): two or more variants of one Creative family, each published as its '
    'own Post, decided by a winner rule once every variant has a performance snapshot at or after '
    'fire_time + window_hours. See docs/creatives.md.';
