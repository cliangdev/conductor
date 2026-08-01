ALTER TABLE webhook_event ADD COLUMN trace_id VARCHAR(64);

CREATE INDEX idx_webhook_event_trace_id ON webhook_event (trace_id);
