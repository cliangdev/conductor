-- One monthly paid budget per workspace and the stop rules that guard each paid channel. Additive only.
--
-- marketing_budget     the cap for one calendar month (month is 'YYYY-MM', UTC). A month without a row has no cap:
--                      pacing then shows spend without a cap, never a cap of 0.
-- marketing_stop_rule  "stop (or review) this channel when <metric> is <comparator> <threshold> over the last
--                      <window_days> settled days, once at least <min_sample> taps back it". Conductor only records
--                      and evaluates a rule; any bid or budget change is made by a human in the ad platform.
--
-- Rules are removed softly (active = false) so a dropped rule stays on record. The application validates the
-- channel, scope and metric vocabulary, so a new metric needs no migration.

CREATE TABLE marketing_budget (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    month       CHAR(7) NOT NULL,
    cap_amount  NUMERIC(14, 2) NOT NULL,
    currency    VARCHAR(3) NOT NULL DEFAULT 'USD',
    updated_by  VARCHAR(36),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_budget UNIQUE (project_id, month),
    CONSTRAINT ck_marketing_budget_month CHECK (month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_marketing_budget_cap CHECK (cap_amount > 0)
);

CREATE TABLE marketing_stop_rule (
    id           VARCHAR(36) PRIMARY KEY,
    project_id   VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    channel      VARCHAR(32) NOT NULL,
    -- 'channel' (the channel's total rows), 'campaign:<id>' or 'keyword:<id>' (dimension rows with that id)
    scope        VARCHAR(200) NOT NULL DEFAULT 'channel',
    -- cost_per_install | spend | installs | taps | tap_through_rate | install_rate
    metric       VARCHAR(64) NOT NULL,
    comparator   VARCHAR(2) NOT NULL,
    threshold    NUMERIC NOT NULL,
    -- taps (for Apple Ads) the window must hold before the rule can trip; 0 = no minimum
    min_sample   INTEGER NOT NULL DEFAULT 0,
    window_days  INTEGER NOT NULL DEFAULT 7,
    note         TEXT,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by   VARCHAR(36),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_marketing_stop_rule_comparator CHECK (comparator IN ('GT', 'LT')),
    CONSTRAINT ck_marketing_stop_rule_min_sample CHECK (min_sample >= 0),
    CONSTRAINT ck_marketing_stop_rule_window CHECK (window_days BETWEEN 1 AND 90)
);

CREATE INDEX idx_marketing_stop_rule_project_active ON marketing_stop_rule (project_id, active);

COMMENT ON TABLE marketing_budget IS
    'Monthly paid-marketing cap per workspace; no row for a month means no cap is set.';
COMMENT ON TABLE marketing_stop_rule IS
    'Per-channel (or campaign / keyword) stop rule evaluated over settled days; Conductor proposes and records only.';
