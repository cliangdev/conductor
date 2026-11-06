-- Growth signals (RE-99 T7.3): a seasonal moments calendar, a weekly ratings program and a competitor watch,
-- all from public data. Additive only.
--
-- Computed on read, never stored: a moment's status, a surface's warning, weekly ratings growth and the
-- competitor change list. A column that was not reported stays NULL (never 0): a price, a rating or a rank
-- that the public API did not return reads as unknown.

-- A dated moment (Thanksgiving, a launch week) and the surfaces that must change ahead of it.
CREATE TABLE marketing_moment (
    id         VARCHAR(36)  PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    key        VARCHAR(64)  NOT NULL,
    name       TEXT         NOT NULL,
    date       DATE         NOT NULL,
    lead_days  INT          NOT NULL DEFAULT 21 CHECK (lead_days >= 0),
    created_by VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_moment UNIQUE (project_id, key)
);

-- One place the moment needs a change ("App Store promotional text", "TikTok post", "Email"), due by due_on.
-- change_work_item_id is the MARKETING_CHANGES Work Item that recorded the change; NULL until one is linked,
-- and NULL again if that Work Item is deleted (the warning then returns).
CREATE TABLE marketing_moment_surface (
    id                  VARCHAR(36)  PRIMARY KEY,
    moment_id           VARCHAR(36)  NOT NULL REFERENCES marketing_moment(id) ON DELETE CASCADE,
    surface             VARCHAR(120) NOT NULL,
    due_on              DATE         NOT NULL,
    change_work_item_id VARCHAR(36)  REFERENCES work_items(id) ON DELETE SET NULL,
    CONSTRAINT uq_marketing_moment_surface UNIQUE (moment_id, surface)
);

CREATE INDEX idx_marketing_moment_surface_change ON marketing_moment_surface (change_work_item_id)
    WHERE change_work_item_id IS NOT NULL;

-- An app we watch besides our own. Removing one sets active = false so its history stays readable.
CREATE TABLE marketing_competitor (
    id         VARCHAR(36)  PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    app_id     VARCHAR(32)  NOT NULL,
    name       TEXT         NOT NULL,
    country    VARCHAR(2)   NOT NULL DEFAULT 'us',
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_competitor UNIQUE (project_id, app_id)
);

-- Keywords whose App Store search position is recorded for every watched app each week. Used when the PRODUCT
-- feed carries no aso:<keyword> rows; the job reads at most 25 per project per week.
CREATE TABLE marketing_tracked_keyword (
    id         VARCHAR(36)  PRIMARY KEY,
    project_id VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    keyword    VARCHAR(100) NOT NULL,
    country    VARCHAR(2)   NOT NULL DEFAULT 'us',
    CONSTRAINT uq_marketing_tracked_keyword UNIQUE (project_id, keyword, country)
);

-- One public listing reading per app per ISO week (captured_on is that week's Monday, UTC). The unique key
-- makes the weekly job idempotent. description_hash is the sha256 of the listing description, so a rewrite is
-- detectable without storing the text. keyword_ranks is {keyword: 1-based position | null}; null means the app
-- was not among the first 50 results. country is the App Store storefront the reading came from: two snapshots
-- of different storefronts are never compared (their prices and rating counts mean different things).
CREATE TABLE marketing_app_snapshot (
    id               VARCHAR(36)   PRIMARY KEY,
    project_id       VARCHAR(36)   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    app_id           VARCHAR(32)   NOT NULL,
    captured_on      DATE          NOT NULL,
    country          VARCHAR(2),
    price            NUMERIC(12,2),
    currency         VARCHAR(8),
    rating_avg       NUMERIC(6,4),
    rating_count     BIGINT,
    version          VARCHAR(64),
    title            TEXT,
    subtitle         TEXT,
    description_hash VARCHAR(64),
    keyword_ranks    JSONB,
    CONSTRAINT uq_marketing_app_snapshot UNIQUE (project_id, app_id, captured_on)
);

-- Recent App Store reviews of a watched app (ours or a competitor's), as the public RSS feed returns them.
CREATE TABLE marketing_review (
    id          VARCHAR(36)  PRIMARY KEY,
    project_id  VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    app_id      VARCHAR(32)  NOT NULL,
    review_id   VARCHAR(64)  NOT NULL,
    rating      INT,
    title       TEXT,
    body        TEXT,
    author      TEXT,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_marketing_review UNIQUE (project_id, app_id, review_id)
);

CREATE INDEX idx_marketing_review_recent ON marketing_review (project_id, app_id, reviewed_at DESC);
