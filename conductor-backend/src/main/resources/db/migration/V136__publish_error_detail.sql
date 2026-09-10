-- The UI shows a destination's error_message / a connection's health_message verbatim, and both have
-- carried the platform's whole error, JSON body included, since day one. These columns split that: the
-- existing message columns now hold a short, human sentence (PlatformErrorText.humanize), and these new
-- columns hold the full original (PlatformErrorText.detail) for anyone who needs to see exactly what the
-- platform said.
ALTER TABLE post_publish_target ADD COLUMN error_detail TEXT;
ALTER TABLE connection ADD COLUMN health_detail TEXT;
