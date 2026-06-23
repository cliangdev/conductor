-- Persist the reason a connector's live fetch failed, so the UI can surface it on the next page
-- load (the GET path) instead of only on a forced refresh.
--
-- Until now a failed fetch produced a DEGRADED ConnectorData carrying an error string, but the
-- cache row only stored data_json/health_status/fetched_at — the message was dropped, and the read
-- paths hardcoded errorMessage = null. Adding a nullable column lets the failure reason round-trip.
ALTER TABLE connection_data_cache ADD COLUMN error_message TEXT;
