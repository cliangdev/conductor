-- A settled destination (published, failed, taken down) is history: it keeps its account label and link
-- after the connection it published through is disconnected, and only the reference to the deleted
-- connection row goes. V122's check made every non-manual row keep a connection so a bug could never
-- silently turn a scheduled target into one nothing publishes; that guarantee still holds for every row
-- that has not settled, which is the only kind the guarantee was ever about.
ALTER TABLE post_publish_target DROP CONSTRAINT ck_post_publish_target_manual_has_no_connection;
ALTER TABLE post_publish_target ADD CONSTRAINT ck_post_publish_target_manual_has_no_connection
    CHECK (
        ((lane = 'MANUAL') = (connection_id IS NULL) AND (lane = 'MANUAL') = (connector_id IS NULL))
        OR (lane <> 'MANUAL' AND state IN ('PUBLISHED', 'FAILED', 'REVOKED'))
    );
