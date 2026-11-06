-- Switchback incrementality tests (RE-99 T6.3): one Apple Ads keyword is switched on and off by week to see
-- whether paid adds installs or only relabels organic ones. A human applies the switch (the ad platform, or a
-- gated tool); Conductor declares the plan, says which state each week should be in, and settles the result itself.
-- Additive only.
--
-- weeks          [{"weekStart": "YYYY-MM-DD" (a Monday, UTC), "on": true|false}], consecutive weeks, fixed once the
--                test is RUNNING.
-- decision_rule  {"metric": "organic_search_first_time_downloads", "minLift": n, "maxCannibalisationPct": n}, fixed
--                up front. The sentence that explains it is computed on read from these fields, never stored.
-- min_n          the fewest first-time App Store search downloads, across all weeks, a verdict may rest on.
-- status         DRAFT -> RUNNING -> SETTLED | INCONCLUSIVE, or CANCELLED from DRAFT / RUNNING.
-- verdict        worked | did_not_work | no_effect | inconclusive; NULL until the test settles. These are the
--                MARKETING_CHANGES verdict options, so the change record and the test always say the same word.
-- result         the settled snapshot (per-week figures, on/off means, n reached); NULL until the test settles.
--                Stored, not recomputed, because later restatements of the daily rows must not rewrite a verdict
--                that was already recorded as a change.
-- change_work_item_id  the REVIEWED MARKETING_CHANGES Work Item that recorded the verdict; NULL until settled.
--                Deleting that Work Item keeps the test.

CREATE TABLE marketing_switchback_test (
    id                  VARCHAR(36)  PRIMARY KEY,
    project_id          VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name                VARCHAR(200) NOT NULL,
    keyword_id          VARCHAR(64)  NOT NULL,
    keyword_text        VARCHAR(200) NOT NULL,
    weeks               JSONB        NOT NULL,
    decision_rule       JSONB        NOT NULL,
    min_n               INTEGER      NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    verdict             VARCHAR(32),
    result              JSONB,
    change_work_item_id VARCHAR(36)  REFERENCES work_items(id) ON DELETE SET NULL,
    created_by          VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    started_at          TIMESTAMP WITH TIME ZONE,
    settled_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_marketing_switchback_status
        CHECK (status IN ('DRAFT', 'RUNNING', 'SETTLED', 'INCONCLUSIVE', 'CANCELLED')),
    CONSTRAINT ck_marketing_switchback_verdict
        CHECK (verdict IS NULL OR verdict IN ('worked', 'did_not_work', 'no_effect', 'inconclusive')),
    CONSTRAINT ck_marketing_switchback_min_n CHECK (min_n >= 1)
);

CREATE INDEX idx_marketing_switchback_project ON marketing_switchback_test (project_id, created_at DESC);
CREATE INDEX idx_marketing_switchback_running ON marketing_switchback_test (status) WHERE status = 'RUNNING';
CREATE INDEX idx_marketing_switchback_change ON marketing_switchback_test (change_work_item_id)
    WHERE change_work_item_id IS NOT NULL;

COMMENT ON TABLE marketing_switchback_test IS
    'Weekly on/off test of one Apple Ads keyword; the plan is fixed once RUNNING and the test settles itself into a change record.';
