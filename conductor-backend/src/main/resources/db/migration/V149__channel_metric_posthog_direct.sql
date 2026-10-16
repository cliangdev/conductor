-- PostHog reports a visit with no referrer as the literal '$direct'; the feed now stores it as '(direct)'.
-- Rename rows already stored, dropping a '$direct' row where the same day already has '(direct)'.
DELETE FROM channel_metric_daily d
WHERE d.connector_id = 'posthog'
  AND d.metric = 'sessions_by_referrer'
  AND d.dimension_key = '$direct'
  AND EXISTS (SELECT 1 FROM channel_metric_daily o
              WHERE o.connection_id = d.connection_id AND o.metric = d.metric
                AND o.day = d.day AND o.dimension_key = '(direct)');

UPDATE channel_metric_daily
SET dimension_key = '(direct)'
WHERE connector_id = 'posthog'
  AND metric = 'sessions_by_referrer'
  AND dimension_key = '$direct';
