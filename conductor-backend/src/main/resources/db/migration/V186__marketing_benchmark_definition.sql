-- What a benchmark actually measures, so it is only judged against a metric with the same definition.
-- Additive only: every existing row keeps NULLs and reads as "definition missing".
--
-- numerator    a measure name (e.g. total_downloads) or product:<key>; NULL when the definition is unknown.
-- denominator  a measure name or product:<key>; NULL for a count (or when unknown).
-- source_type  ALL, APP_STORE_SEARCH, APP_STORE_BROWSE, ...; NULL means ALL once a numerator is set.
--
-- The application validates the vocabulary (BenchmarkComparability), so new measures need no migration.

ALTER TABLE marketing_benchmark
    ADD COLUMN numerator   VARCHAR(64),
    ADD COLUMN denominator VARCHAR(64),
    ADD COLUMN source_type VARCHAR(32);

COMMENT ON COLUMN marketing_benchmark.numerator IS
    'Measure the benchmark counts (e.g. total_downloads or product:<key>); NULL = definition missing.';
COMMENT ON COLUMN marketing_benchmark.denominator IS
    'Measure the numerator is divided by; NULL for a count.';
COMMENT ON COLUMN marketing_benchmark.source_type IS
    'Traffic source the benchmark covers (ALL, APP_STORE_SEARCH, ...); NULL means ALL.';
