ALTER TABLE github_webhook_events
    ALTER COLUMN payload TYPE TEXT USING payload::TEXT;
