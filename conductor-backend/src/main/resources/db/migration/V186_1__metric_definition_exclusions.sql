-- Product metrics contract v3: what a metric's value leaves out, and a rank unit. Additive only.
--
-- exclusions   one plain line, at most 240 characters, e.g. 'Likely App Review installs (7 in the window)'.
--              NULL when the feed does not say; never an empty string.
-- unit         POSITION joins COUNT, CURRENCY and PERCENT, for a rank such as an organic App Store position.
--
-- The unit check was declared inline in V150, so Postgres named it channel_metric_definition_unit_check.

ALTER TABLE channel_metric_definition
    ADD COLUMN exclusions TEXT;

ALTER TABLE channel_metric_definition
    DROP CONSTRAINT IF EXISTS channel_metric_definition_unit_check;

ALTER TABLE channel_metric_definition
    ADD CONSTRAINT channel_metric_definition_unit_check
        CHECK (unit IN ('COUNT', 'CURRENCY', 'PERCENT', 'POSITION'));

COMMENT ON COLUMN channel_metric_definition.exclusions IS
    'What the metric value leaves out, as the feed states it (contract v3); NULL when not stated.';
