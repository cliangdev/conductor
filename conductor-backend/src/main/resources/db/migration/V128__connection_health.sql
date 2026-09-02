-- COND-23: connection health, distinct from connection status.
--
-- `status` answers "does this project want this connection?" (ACTIVE / DISABLED / ...). It is a
-- user-owned lifecycle value and nothing here ever writes it. `health_status` answers a different
-- question -- "did the platform still accept our credentials the last time we used them?" -- and is
-- owned entirely by the system. An expired or revoked social account is therefore still ACTIVE and
-- still on the row; it is merely UNHEALTHY, so the Integrations UI can say so instead of the
-- connection failing silently at publish time.
--
-- Not to be confused with connection_data_cache.health_status (V53), which grades the last data
-- *fetch* for pull connectors. These columns grade the *connection* itself, and so also cover push
-- connectors that never fill a data cache.
--
-- All three are NULLABLE with no default and no backfill: null means "never checked", which is the
-- honest state of every pre-existing row, and is deliberately distinct from HEALTHY ("checked, and
-- the platform accepted us").
ALTER TABLE connection
    ADD COLUMN health_status     VARCHAR(16),   -- HEALTHY | UNHEALTHY (null = never checked)
    ADD COLUMN health_checked_at TIMESTAMP WITH TIME ZONE,
    -- The platform's own words, verbatim where possible, so a human reading the row learns what to
    -- do about it ("Token has been expired or revoked"). TEXT because provider error bodies are
    -- not length-bounded.
    ADD COLUMN health_message    TEXT;

-- Supports the "which connections need attention?" scan. Partial: unhealthy rows are the rare
-- minority, so indexing only them keeps the index near-empty in the healthy steady state.
CREATE INDEX idx_connection_unhealthy
    ON connection (project_id)
    WHERE health_status = 'UNHEALTHY';
