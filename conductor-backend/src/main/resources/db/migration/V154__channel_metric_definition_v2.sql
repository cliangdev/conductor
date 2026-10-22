-- Product metrics contract v2: what a metric counts and how a funnel stage is counted. Additive only.
--
-- basis            EVENT (default) or COHORT: a COHORT metric's day is the day its members first opened, and its value
--                  is how many of them reached the stage by the feed's dataCompleteThrough.
-- area             ACQUISITION, ACTIVATION, RETENTION or REVENUE, when the feed groups its scorecard.
-- description      the human "basis" text, e.g. 'Installs that started a free trial'.
-- headline         priority among the feed's headline metrics (1 first).
-- watch_band_pct   how close a miss counts as WATCH rather than OFF_TRACK, in percent of the target (null = 10).

ALTER TABLE channel_metric_definition
    ADD COLUMN basis          TEXT NOT NULL DEFAULT 'EVENT' CHECK (basis IN ('EVENT', 'COHORT')),
    ADD COLUMN area           TEXT CHECK (area IN ('ACQUISITION', 'ACTIVATION', 'RETENTION', 'REVENUE')),
    ADD COLUMN description    TEXT,
    ADD COLUMN headline       INTEGER,
    ADD COLUMN watch_band_pct NUMERIC;
