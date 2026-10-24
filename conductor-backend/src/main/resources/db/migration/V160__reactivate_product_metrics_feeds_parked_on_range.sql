-- Product-metrics feeds were parked SETUP_REQUIRED when the puller asked for a 46-day range and the metrics
-- server answered HTTP 400 "the range is at most 31 days". The puller now stays within 14 days per request, so
-- bring those feeds back: ACTIVE, due now, cursor cleared so the next pull backfills the full history window
-- (new v2 metrics only had a few days). Narrow by design: only product-metrics feeds still parked on that
-- exact error; re-running matches nothing.
UPDATE connector_feed
SET status = 'ACTIVE',
    last_error = NULL,
    consecutive_failures = 0,
    cursor_state = NULL,
    cursor_updated_at = NOW(),
    next_run_at = NOW(),
    updated_at = NOW()
WHERE connector_id = 'product-metrics'
  AND status = 'SETUP_REQUIRED'
  AND last_error LIKE '%the range is at most 31 days%';
