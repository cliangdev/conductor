-- #8 — scope webhook idempotency to (connection_id, delivery_id), not globally to delivery_id.
--
-- A single provider delivery (one X-GitHub-Delivery) now fans out to N connections — one durable
-- webhook_event row per target connection (app-level GitHub App: the same installation connected from
-- multiple projects). The old global UNIQUE on delivery_id (V53) made that impossible: the 2nd..Nth
-- row collided. Replace it with a composite UNIQUE on (connection_id, delivery_id): a connection may
-- only see a given delivery once, but different connections may each see the same delivery.

-- Drop the global unique created by `delivery_id VARCHAR(255) UNIQUE` in V53. Postgres auto-named it
-- <table>_<column>_key. Guarded so the migration is safe if the constraint was named differently.
ALTER TABLE webhook_event DROP CONSTRAINT IF EXISTS webhook_event_delivery_id_key;

-- Composite per-connection idempotency key. Partial (delivery_id IS NOT NULL) because some providers
-- send no delivery id — those rows are intentionally not deduped.
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_event_connection_delivery
    ON webhook_event (connection_id, delivery_id)
    WHERE delivery_id IS NOT NULL;
